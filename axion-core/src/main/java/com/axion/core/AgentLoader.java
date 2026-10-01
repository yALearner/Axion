package com.oryxos.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * Agent 目录解析与 Profile 派生（003 交付物 ProfileLoader 更名 + 011-plugin-agent 扩职责，修订说明 ④）。
 *
 * <p>一个目录 = 一个 Agent（宪法 IV）：扫 {@code .oryxos/agents/} 下各子目录，把每个 {@code AGENT.md} 拆成
 * frontmatter（运行配置）与正文（任务指令）——拆分与派生**同一解析器**（坑一：两套解析各拆各的会 frontmatter/正文错位）； 认出 {@code
 * scripts/}、{@code skills/}、{@code REFERENCE.md} 资源所在；{@code deriveProfile} 把 frontmatter 派生成底座认识的
 * {@link Profile}（正文不进 Profile 值对象——由 {@link ContextLoader} 每轮现读，修订说明 ⑤）。
 *
 * <p>校验范围（失败报错点名文件与键，坑二）：缺 {@code name}/{@code provider}、provider 引用全局层不存在（001 口径）、 {@code name}
 * 与目录名不一致（坑八：唯一标识 = 目录名，不校验则注册成功、首次触发才炸的晚失败）、schedules 缺 id （010 ⑦c）。校验失败的 Agent
 * 不阻断启动（记错误日志、跳过注册，003 口径）。
 */
public final class AgentLoader {

  private static final Logger LOG = LoggerFactory.getLogger(AgentLoader.class);

  private static final String AGENT_FILE = "AGENT.md";

  /** AGENT.md 拆分结果：frontmatter（YAML 映射）+ 正文（frontmatter 之后原样文本，不做二次加工——坑一）。 */
  public record FrontMatterAndBody(Map<String, Object> frontmatter, String body) {

    public FrontMatterAndBody {
      frontmatter = new LinkedHashMap<>(frontmatter); // 防御性拷贝：不保留外部可变 Map 引用（Profile 先例）
    }

    @Override
    public Map<String, Object> frontmatter() {
      return Collections.unmodifiableMap(frontmatter); // 不暴露可变集合的内部表示（Profile 先例）
    }
  }

  /**
   * 拆 AGENT.md 内容：frontmatter（首个 --- 分隔的 YAML 头，YAML 映射）+ 正文（原样文本）。
   *
   * @throws IllegalArgumentException frontmatter 缺失/未闭合/非 YAML 映射时点名文件（003 口径）
   */
  public static FrontMatterAndBody split(String content, Path agentFile) {
    int start = content.indexOf("---");
    if (start < 0) {
      throw new IllegalArgumentException("AGENT.md 缺少 frontmatter（--- 分隔的 YAML 头）: " + agentFile);
    }
    int end = content.indexOf("---", start + 3);
    if (end < 0) {
      throw new IllegalArgumentException("AGENT.md frontmatter 未闭合: " + agentFile);
    }
    String yamlText = content.substring(start + 3, end);
    Object loaded = new Yaml(new LoaderOptions()).load(yamlText);
    if (loaded == null) {
      return new FrontMatterAndBody(Map.of(), content.substring(end + 3));
    }
    if (!(loaded instanceof Map<?, ?> rawMap)) {
      throw new IllegalArgumentException("AGENT.md frontmatter 不是合法 YAML 映射: " + agentFile);
    }
    @SuppressWarnings("unchecked")
    Map<String, Object> map = (Map<String, Object>) rawMap;
    return new FrontMatterAndBody(map, content.substring(end + 3));
  }

  /**
   * 现读并返回 Agent 正文（去 frontmatter）——ContextLoader 每轮调用，无缓存（坑三：缓存正文则"改完即时生效"破产）。
   *
   * @throws IllegalArgumentException 缺 AGENT.md 时点名路径
   */
  public String loadBody(Path agentDir) {
    Path agentFile = agentDir.resolve(AGENT_FILE);
    if (!Files.isRegularFile(agentFile)) {
      throw new IllegalArgumentException("Agent 目录缺少 " + AGENT_FILE + ": " + agentDir);
    }
    String content;
    try {
      content = Files.readString(agentFile, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalArgumentException("读取 AGENT.md 失败: " + agentFile, e);
    }
    return split(content, agentFile).body();
  }

  /** 认出资源：{@code scripts/}、{@code skills/} 目录与 {@code REFERENCE.md} 文件中存在的集合。 */
  public Set<String> detectResources(Path agentDir) {
    Set<String> resources = new LinkedHashSet<>();
    if (Files.isDirectory(agentDir.resolve("scripts"))) {
      resources.add("scripts");
    }
    if (Files.isDirectory(agentDir.resolve("skills"))) {
      resources.add("skills");
    }
    if (Files.isRegularFile(agentDir.resolve("REFERENCE.md"))) {
      resources.add("REFERENCE.md");
    }
    return Collections.unmodifiableSet(resources);
  }

  /**
   * 从单个 Agent 目录派生 Profile。校验失败抛 {@link IllegalArgumentException}（信息清晰、点名文件与键）。
   *
   * @param agentDir Agent 目录（含 AGENT.md）
   * @param providerNames 全局层声明的 Provider 名集合
   */
  public Profile deriveProfile(Path agentDir, Set<String> providerNames) {
    Path agentFile = agentDir.resolve(AGENT_FILE);
    if (!Files.isRegularFile(agentFile)) {
      throw new IllegalArgumentException("Agent 目录缺少 " + AGENT_FILE + ": " + agentDir);
    }
    String content;
    try {
      content = Files.readString(agentFile, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalArgumentException("读取 AGENT.md 失败: " + agentFile, e);
    }
    Map<String, Object> fm = split(content, agentFile).frontmatter();

    String name =
        stringValue(fm, "name")
            .orElseThrow(
                () ->
                    new IllegalArgumentException("AGENT.md frontmatter 缺少必填项 name: " + agentFile));
    Path fileName =
        agentDir.getFileName(); // 单次取值：SpotBugs NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE 契约——二次调用判空无效
    String dirName = fileName == null ? "" : fileName.toString();
    // 坑八：唯一标识 = 目录名——不校验则注册成功、ContextLoader 按 profile.name 找错目录、首次触发才炸的晚失败
    if (!name.equals(dirName)) {
      throw new IllegalArgumentException(
          "Agent 目录名与 frontmatter name 不一致: 目录 " + dirName + " / frontmatter " + name);
    }
    String description = stringValue(fm, "description").orElse(null);

    Map<?, ?> identity = mapValue(fm, "identity").orElse(Map.of());
    String agentName = stringValue(identity, "agent_name").orElse(null);
    String prompt = stringValue(identity, "prompt").orElse(null);

    Map<?, ?> provider =
        mapValue(fm, "provider")
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "Agent [" + name + "] frontmatter 缺少 provider 段: " + agentFile));
    String providerName = stringValue(provider, "name").orElse(null);
    if (providerName == null || !providerNames.contains(providerName)) {
      throw new IllegalArgumentException(
          "Agent ["
              + name
              + "] 引用的 provider 不存在: "
              + providerName
              + "（全局层已声明: "
              + providerNames
              + "）");
    }
    String model = stringValue(provider, "model").orElse(null);
    Double temperature = doubleValue(provider, "temperature").orElse(null);

    List<String> tools = stringList(fm, "tools");
    List<String> mcpServers = stringList(fm, "mcp_servers");
    List<String> channels = channelNames(fm);
    List<String> bootstrap = stringList(fm, "bootstrap");
    List<Profile.Schedule> schedules = schedules(fm, name);

    Map<?, ?> settings = mapValue(fm, "settings").orElse(Map.of());
    Integer maxIterations = intValue(settings, "max_iterations").orElse(null);
    Integer maxHistoryTurns = intValue(settings, "max_history_turns").orElse(null);

    return new Profile(
        name,
        description,
        new Profile.Identity(agentName, prompt),
        new Profile.ProviderRef(providerName, model, temperature),
        tools,
        mcpServers,
        channels,
        schedules,
        bootstrap,
        new Profile.Settings(maxIterations, maxHistoryTurns));
  }

  /**
   * 扫 agents 根目录加载全部 Profile。单目录失败记错误日志并跳过，不阻断启动。 （失败目录的路径细节在异常消息/堆栈里，不单独作为日志参数，避免外部可控值直入日志。）
   *
   * @return name → Profile（仅校验通过的）
   */
  public Map<String, Profile> loadAll(Path agentsRoot, Set<String> providerNames) {
    Map<String, Profile> result = new LinkedHashMap<>();
    if (!Files.isDirectory(agentsRoot)) {
      return result;
    }
    try (Stream<Path> dirs = Files.list(agentsRoot)) {
      dirs.filter(Files::isDirectory)
          .sorted()
          .forEach(
              dir -> {
                try {
                  Profile profile = deriveProfile(dir, providerNames);
                  result.put(profile.name(), profile);
                } catch (RuntimeException e) {
                  LOG.error("Agent 加载失败，已跳过", e);
                }
              });
    } catch (IOException e) {
      LOG.error("扫描 agents 目录失败", e);
    }
    return result;
  }

  private Optional<String> stringValue(Map<?, ?> map, String key) {
    Object value = map.get(key);
    return value == null ? Optional.empty() : Optional.of(String.valueOf(value));
  }

  private Optional<Map<?, ?>> mapValue(Map<?, ?> map, String key) {
    Object value = map.get(key);
    if (value instanceof Map<?, ?> nestedMap) {
      return Optional.of(nestedMap);
    }
    return Optional.empty();
  }

  private Optional<Integer> intValue(Map<?, ?> map, String key) {
    Object value = map.get(key);
    if (value instanceof Number number) {
      return Optional.of(number.intValue());
    }
    return Optional.empty();
  }

  private Optional<Double> doubleValue(Map<?, ?> map, String key) {
    Object value = map.get(key);
    if (value instanceof Number number) {
      return Optional.of(number.doubleValue());
    }
    return Optional.empty();
  }

  private List<String> stringList(Map<?, ?> map, String key) {
    Object value = map.get(key);
    if (value instanceof List<?> list) {
      List<String> result = new ArrayList<>();
      for (Object item : list) {
        result.add(String.valueOf(item));
      }
      return result;
    }
    return List.of();
  }

  /** channels 支持 {@code - name: cli} 形态，提取 name 字段。 */
  private List<String> channelNames(Map<?, ?> map) {
    Object value = map.get("channels");
    if (!(value instanceof List<?> list)) {
      return List.of();
    }
    List<String> result = new ArrayList<>();
    for (Object item : list) {
      if (item instanceof Map<?, ?> channelMap) {
        Object name = channelMap.get("name");
        if (name != null) {
          result.add(String.valueOf(name));
        }
      } else {
        result.add(String.valueOf(item));
      }
    }
    return result;
  }

  private List<Profile.Schedule> schedules(Map<?, ?> map, String agentName) {
    Object value = map.get("schedules");
    if (!(value instanceof List<?> list)) {
      return List.of();
    }
    List<Profile.Schedule> result = new ArrayList<>();
    for (Object item : list) {
      if (item instanceof Map<?, ?> scheduleMap) {
        String id = stringValue(scheduleMap, "id").orElse(null);
        if (id == null || id.isBlank()) {
          // ⑦c：id 缺失启动报错——不静默、不派生兜底（兜底退回 008 派生 key 即断链风险复活）
          throw new IllegalArgumentException("Agent [" + agentName + "] schedules 条目缺少必填项 id");
        }
        result.add(
            new Profile.Schedule(
                id,
                stringValue(scheduleMap, "cron").orElse(null),
                stringValue(scheduleMap, "zone").orElse(null),
                stringValue(scheduleMap, "message").orElse(null)));
      }
    }
    return Collections.unmodifiableList(result);
  }
}
