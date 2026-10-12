package com.axion.core;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * Agent 生命周期编排者（012-agent-mgmt FR-2）——30 节唯一新增的编排组件：把 29 节立好的派生/注册/校验能力按顺序串起来、 失败回滚。
 *
 * <p>核心不变量：{@link #register(Path)} 是三条录入路径（API 上传、WorkspaceWatcher 事件、启动扫描）的<strong>唯一注册
 * 入口</strong>（坑十七——同一套校验、同一异常、同一消息）；{@link #delete} 顺序钉死「注销定时 → 移出索引 → 归档」（坑十）； {@link #create}
 * 失败回滚已写目录、不留半个 Agent（坑十一）；重名第一步就拒、一个目录都不写（坑十八）。
 *
 * <p>落 axion-core：生成调用经 {@link LlmGateway} 端口（依赖倒置，001 先例——避免 core→provider 反向依赖）；tools 引用
 * 告警校验以装配层注入的钩子接入（011 债②，core 不反向依赖 tool）；生成用 provider/model 取系统默认配置
 * （axion.author.*，装配处注入字符串——坑十五：model 留空则生成抛 {@link IllegalArgumentException}，由 web 层映射 503）。
 *
 * <p>纯类无组件注解（AgentScheduler 先例），装配处显式 @Bean。
 */
public final class AgentLifecycleService {

  /** 生成模型未配置的报错文案（坑十五）——web 层按此前缀映射 503 AUTHOR_MODEL_MISSING。 */
  public static final String MODEL_MISSING_MESSAGE = "生成模型未配置: axion.author.model（配置后重试）";

  /**
   * 生成用系统提示词（5.2.4，AGENT_AUTHOR_PROMPT——落位修正 2026-10-02：prompts/prompts.md
   * 为用户提示词历史日志，运行时提示词以常量内嵌本类）。
   */
  static final String AGENT_AUTHOR_PROMPT =
      "你是一名资深 Agent 工程师。用户会给你一句话描述，你要产出一份 Axion 平台合法的 AGENT.md（Markdown 格式）："
          + System.lineSeparator()
          + "1. 以 YAML frontmatter 开头：name（英文小写连字符）、description、identity.agent_name（中文昵称）、"
          + "identity.prompt、provider（name 必填，model 可省）、tools（数组，只列底座已注册的内置工具名）、"
          + "schedules（可选，id/cron（六段含秒）/zone/message）。"
          + System.lineSeparator()
          + "2. frontmatter 之后是任务指令正文：分步骤、可执行、不臆测。"
          + System.lineSeparator()
          + "3. 只输出 AGENT.md 内容本身，不要任何额外解释，不要代码围栏。";

  private final AgentLoader agentLoader;
  private final ProfileRegistry profileRegistry;
  private final AgentScheduler agentScheduler;
  private final AgentStore agentStore;
  private final LlmGateway llmGateway;
  private final Consumer<Profile> toolRefValidator;
  private final Set<String> providerNames;
  private final String authorProvider;
  private final String authorModel;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "AgentLoader/ProfileRegistry/AgentScheduler/AgentStore/LlmGateway/钩子为装配处注入的单例（只读使用、不暴露引用）——AgentScheduler"
              + " 同款先例")
  public AgentLifecycleService(
      AgentLoader agentLoader,
      ProfileRegistry profileRegistry,
      AgentScheduler agentScheduler,
      AgentStore agentStore,
      LlmGateway llmGateway,
      Consumer<Profile> toolRefValidator,
      Set<String> providerNames,
      String authorProvider,
      String authorModel) {
    this.agentLoader = agentLoader;
    this.profileRegistry = profileRegistry;
    this.agentScheduler = agentScheduler;
    this.agentStore = agentStore;
    this.llmGateway = llmGateway;
    this.toolRefValidator = toolRefValidator;
    this.providerNames = Set.copyOf(providerNames);
    this.authorProvider = authorProvider;
    this.authorModel = authorModel;
  }

  /**
   * 注册单个 Agent 目录——API 上传写完目录后同步调它、WorkspaceWatcher 监听到变更也调它。**同一段代码**（坑十七）。
   *
   * <p>重注册语义（watcher 修改事件 / update 复用）：schedules 与原注册一致 → 只更新注册表（定时句柄不动）； schedules 变更 →
   * 先注销旧句柄、再注册新的（坑：否则旧 cron 跟新 cron 一起跑）。
   */
  public Profile register(Path agentDir) {
    return doRegister(agentLoader.deriveProfile(agentDir, providerNames));
  }

  /** 创建（四步编排）：重名第一步就拒（坑十八）→ 脚手架写目录 → 注册 → 失败回滚已写目录（坑十一）。 */
  @SuppressFBWarnings(
      value = "THROWS_METHOD_THROWS_RUNTIMEEXCEPTION",
      justification =
          "回滚后原样重抛注册异常（坑十一：不留半个 Agent）——AgentApiController.runWithTimeout 同款先例（按原类型上抛，刻意重抛"
              + " RuntimeException）")
  public Profile create(String name, String description) {
    if (profileRegistry.exists(name)) {
      throw new IllegalStateException("Agent 已存在: " + name); // 防御：正常路径由 web 层预检（400 口径）
    }
    Path agentDir =
        agentStore.writeAll(
            name, agentStore.scaffold(name, description, authorProvider, authorModel));
    try {
      return doRegister(agentLoader.deriveProfile(agentDir, providerNames));
    } catch (RuntimeException e) {
      agentStore.delete(agentDir); // 坑十一：不留半个 Agent
      throw e;
    }
  }

  /** 更新：整份 AGENT.md 覆写（语义最简）→ 派生校验 → 重注册（schedules 变更由 doRegister 统一先注销旧句柄）。 */
  public Profile update(String name, String agentMarkdown) {
    if (profileRegistry.findByName(name).isEmpty()) {
      throw new IllegalStateException("Agent 不存在: " + name);
    }
    agentStore.write(name, agentMarkdown);
    Profile updated = agentLoader.deriveProfile(agentStore.agentDirOf(name), providerNames);
    return doRegister(updated);
  }

  /** 注销（手工删目录的对称路径，坑十七——与 API 删除共用同一段注销代码）：停定时 → 移出索引；不归档（目录已不在）。 不存在时静默（幂等注销——watcher 重复事件安全）。 */
  public void unregister(String name) {
    profileRegistry
        .findByName(name)
        .ifPresent(
            profile -> {
              agentScheduler.unregisterProfile(profile); // ① 先停定时
              profileRegistry.remove(name); // ② 再移出索引
            });
  }

  /** 删除（顺序钉死，坑十）：注销定时 → 移出索引 → 归档（不物理删，可追溯）。 */
  public void delete(String name) {
    if (profileRegistry.findByName(name).isEmpty()) {
      throw new IllegalStateException("Agent 不存在: " + name);
    }
    unregister(name); // 与手工删目录同一段注销代码（坑十七）
    agentStore.archive(name); // 最后归档 .axion/archive/
  }

  /** 查询；不存在返回空（404 语义由 web 层负责）。 */
  public java.util.Optional<Profile> get(String name) {
    return profileRegistry.findByName(name);
  }

  /**
   * 按描述重生成（5.2.4）：一次 LLM 调用（走 LlmGateway，落 llm_calls 审计由 001 契约保证）产出 AGENT.md → 解析校验 （坑十四：非法抛
   * IllegalArgumentException，web 层映射 400）→ 剥掉模型偶尔多吐的 ``` 代码围栏 → 返回草稿， **不落盘、不注册**。sessionId 由调用方经
   * SessionManager 取得（不变量④：session_id 拼接只在 SessionManager）。
   *
   * <p>坑十五：model 留空抛 IllegalArgumentException（web 层映射 503），不向 OpenAI 兼容端点发 model=null。
   */
  public Map<String, String> generateFiles(String sessionId, String agentName, String description) {
    if (authorModel == null || authorModel.isBlank()) {
      throw new IllegalArgumentException(MODEL_MISSING_MESSAGE);
    }
    // 5.2.4：输出的 provider 若该 Agent 已存在则沿用其 provider（保持可跑）；不存在则用系统默认 author.provider
    Profile existing = profileRegistry.findByName(agentName).orElse(null);
    String providerName =
        existing != null && existing.provider() != null && existing.provider().name() != null
            ? existing.provider().name()
            : authorProvider;
    Profile authorProfile = authorProfile(providerName);
    Prompt prompt =
        new Prompt(
            List.of(
                new SystemMessage(AGENT_AUTHOR_PROMPT),
                new UserMessage(
                    "Agent 名: " + agentName + System.lineSeparator() + "需求描述: " + description)));
    String raw =
        llmGateway.chat(sessionId, authorProfile, prompt).getResult().getOutput().getText();
    String agentMd = stripCodeFences(raw == null ? "" : raw).trim();
    // 坑十四：产出必须能被 AgentLoader 解析（不落盘前机器校验）
    try {
      AgentLoader.split(agentMd, Path.of("AGENT.md"));
    } catch (RuntimeException e) {
      throw new IllegalArgumentException("生成产物不是合法 AGENT.md: " + e.getMessage(), e);
    }
    return Map.of("AGENT.md", agentMd);
  }

  /** 保存一组（可能被用户改过的）文件（5.2.4）：先校验 AGENT.md 可解析（坑十四，不写坏目录）→ 写盘 → 覆写后重注册，写入即生效。 */
  public Profile saveFiles(String name, Map<String, String> files) {
    String agentMd = files.get("AGENT.md");
    if (agentMd == null || agentMd.isBlank()) {
      throw new IllegalArgumentException("保存的文件必须包含 AGENT.md");
    }
    try {
      AgentLoader.split(agentMd, Path.of("AGENT.md"));
    } catch (RuntimeException e) {
      throw new IllegalArgumentException("AGENT.md 不是合法定义: " + e.getMessage(), e);
    }
    agentStore.writeAll(name, files);
    Profile updated = agentLoader.deriveProfile(agentStore.agentDirOf(name), providerNames);
    return doRegister(updated); // schedules 变更由 doRegister 统一先注销旧句柄
  }

  /** 同一段注册代码的落点（register/create/update/saveFiles 共用——坑十七）。 */
  private Profile doRegister(Profile profile) {
    toolRefValidator.accept(profile); // 011 债②：运行时注册重跑 tools 引用告警校验（钩子由装配层注入）
    Profile previous = profileRegistry.findByName(profile.name()).orElse(null);
    boolean schedulesChanged =
        previous == null || !previous.schedules().equals(profile.schedules());
    if (previous != null && schedulesChanged) {
      agentScheduler.unregisterProfile(previous); // 先注销旧句柄
    }
    profileRegistry.register(profile);
    if (schedulesChanged && !profile.schedules().isEmpty()) {
      agentScheduler.registerProfile(profile); // 再注册新句柄
    }
    return profile;
  }

  /** 生成用的临时 Profile：provider/model 取系统默认（author 配置）；无工具（生成不该调工具）。 */
  private Profile authorProfile(String providerName) {
    return new Profile(
        "generator",
        "生成用临时 Profile",
        new Profile.Identity("generator", AGENT_AUTHOR_PROMPT),
        new Profile.ProviderRef(providerName, authorModel, 0.3),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        new Profile.Settings(null, null));
  }

  /** 剥掉模型偶尔多吐的 ``` 代码围栏（5.2.4）：取首个围栏内文本；无围栏则原样。 */
  private String stripCodeFences(String text) {
    int start = text.indexOf("```");
    if (start < 0) {
      return text;
    }
    int contentStart = text.indexOf('\n', start);
    if (contentStart < 0) {
      return text;
    }
    int end = text.indexOf("```", contentStart + 1);
    return end < 0 ? text.substring(contentStart + 1) : text.substring(contentStart + 1, end);
  }
}
