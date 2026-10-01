package com.oryxos.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 上下文加载器（Bootstrap + AGENT.md 正文 + Skill 元数据供给者，归 core——Agent 目录不是 Tool）。
 *
 * <p>三条铁律：① 每次组装 prompt 重新读文件、**不缓存**——用户改完立即生效（坑五/坑三：正文改了即时生效，011 FR-2）；② 显式引用的文件缺失
 * **报错**、Bootstrap 缺失至少 **WARN**——静默跳过会造成"人格悄悄丢了"这类最难查的软故障；③ AGENT.md 缺失**报错**（坑四）。
 *
 * <p>Skill 绑定真相源按宪法 IV 的软连接集合：只注入已绑定 Skill 的 name + description + **绑定路径**（011 修订说明 ⑦：{@code
 * <agentDir>/skills/<name>/SKILL.md}，经软连接/junction 透传读公共实体——配合沙箱 FILE_READ 动态根实现"未绑定不可见"），
 * 正文与附属资源不预载（经 read_file/shell 按需取用）。AGENT.md 正文注入于 011-plugin-agent 交付（FR-2）。
 */
public final class ContextLoader {

  private static final Logger LOG = LoggerFactory.getLogger(ContextLoader.class);

  private final Path workspaceRoot;

  /**
   * @param workspaceRoot 工作区根（即 .oryxos/ 目录：bootstrap 在其根、skills 在其 skills/ 子目录、agents 在其 agents/
   *     子目录）
   */
  public ContextLoader(Path workspaceRoot) {
    this.workspaceRoot = workspaceRoot;
  }

  /** 每轮组装 prompt 时现读（无缓存）：AGENT.md 正文 + Bootstrap 文件 + 已绑定 Skill 元数据。 */
  public String load(Profile profile) {
    StringBuilder context = new StringBuilder();
    context.append(loadBody(profile));
    context.append(loadBootstrap(profile));
    context.append(loadSkillMetadata(profile));
    return context.toString();
  }

  /**
   * AGENT.md 正文（011 FR-2）：每轮从 agents/<name>/AGENT.md 现读、去 frontmatter（坑三：不缓存——正文改了即时生效）；
   * 缺失报错（坑四：不静默）。拆分复用 {@link AgentLoader}（坑一：拆分与派生同一解析器）。
   */
  private String loadBody(Profile profile) {
    Path agentDir = workspaceRoot.resolve("agents").resolve(profile.name());
    String body = new AgentLoader().loadBody(agentDir);
    return body == null || body.isEmpty() ? "" : body + System.lineSeparator();
  }

  private String loadBootstrap(Profile profile) {
    List<String> bootstrap = profile.bootstrap();
    if (bootstrap == null || bootstrap.isEmpty()) {
      // profile 名等用户可控值不入日志参数（防 CRLF 注入，001 先例）
      LOG.warn("Bootstrap 未配置（缺失至少 WARN）");
      return "";
    }
    StringBuilder sb = new StringBuilder();
    for (String file : bootstrap) {
      Path path = workspaceRoot.resolve(file);
      if (!Files.isRegularFile(path)) {
        throw new IllegalStateException("Bootstrap 文件缺失: " + file);
      }
      try {
        sb.append(Files.readString(path)).append(System.lineSeparator());
      } catch (IOException e) {
        throw new IllegalStateException("Bootstrap 文件读取失败: " + file, e);
      }
    }
    return sb.toString();
  }

  private String loadSkillMetadata(Profile profile) {
    Path skillsDir = workspaceRoot.resolve("agents").resolve(profile.name()).resolve("skills");
    if (!Files.isDirectory(skillsDir)) {
      return ""; // 无绑定：跳过
    }
    StringBuilder sb = new StringBuilder();
    try (Stream<Path> entries = Files.list(skillsDir)) {
      List<Path> bindings =
          entries
              .filter(ContextLoader::isBinding)
              .sorted(Comparator.comparing(Path::toString))
              .toList();
      for (Path binding : bindings) {
        sb.append(skillMetadataOf(binding));
      }
    } catch (IOException e) {
      throw new IllegalStateException("Skill 绑定目录读取失败: " + skillsDir, e);
    }
    return sb.toString();
  }

  private String skillMetadataOf(Path binding) {
    Path realDir = resolveRealSkillDir(binding);
    Path realSkillMd = realDir.resolve("SKILL.md");
    if (!Files.isRegularFile(realSkillMd)) {
      throw new IllegalStateException("Skill 缺失 SKILL.md: " + binding.getFileName());
    }
    String content;
    try {
      content = Files.readString(realSkillMd);
    } catch (IOException e) {
      throw new IllegalStateException("Skill 读取失败: " + binding.getFileName(), e);
    }
    String frontmatter = extractFrontmatter(content);
    String name = valueOf(frontmatter, "name");
    String description = valueOf(frontmatter, "description");
    // 011 修订说明 ⑦：注入绑定路径（<agentDir>/skills/<name>/SKILL.md）而非实体真实路径——
    // 配合沙箱 FILE_READ 动态根（= 当前 Agent 目录）实现"未绑定不可见"；技术方案 §12.2 验收口径
    Path bindingSkillMd = binding.toAbsolutePath().normalize().resolve("SKILL.md");
    return "- 技能: "
        + (name == null ? String.valueOf(binding.getFileName()) : name)
        + " — "
        + (description == null ? "" : description)
        + "（读取路径: "
        + bindingSkillMd
        + "）"
        + System.lineSeparator();
  }

  /** 解析绑定真实目标并验证位于公共 Skill 根（.oryxos/skills/）内——逃逸即报错。 */
  private Path resolveRealSkillDir(Path binding) {
    Path real;
    try {
      real = binding.toRealPath(); // 同时解析符号链接与 Windows junction
    } catch (IOException e) {
      throw new IllegalStateException("Skill 绑定解析失败: " + binding.getFileName(), e);
    }
    Path skillsRoot = workspaceRoot.resolve("skills");
    Path realSkillsRoot;
    try {
      realSkillsRoot = skillsRoot.toRealPath();
    } catch (IOException e) {
      // 公共 Skill 根不存在：任何绑定都非法
      throw new IllegalStateException("Skill 绑定目标不在公共 Skill 根: " + binding.getFileName());
    }
    if (!real.startsWith(realSkillsRoot)) {
      throw new IllegalStateException("Skill 绑定目标不在公共 Skill 根: " + binding.getFileName());
    }
    return real;
  }

  /**
   * 绑定判定：POSIX/Windows 符号链接，或 Windows junction（目录挂载点重解析点，以 isOther() 识别）—— 两者都是"文件系统级链接"，同为宪法 IV
   * 的绑定真相源形态（本机无符号链接特权时 junction 是等价落法）。
   */
  static boolean isBinding(Path path) {
    if (Files.isSymbolicLink(path)) {
      return true;
    }
    try {
      BasicFileAttributes attrs =
          Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      return attrs.isOther();
    } catch (IOException e) {
      return false;
    }
  }

  /** 提取 frontmatter（首行 --- 到下一个 --- 之间）。 */
  static String extractFrontmatter(String content) {
    String[] lines = content.split("\\R", -1);
    if (lines.length < 3 || !lines[0].trim().equals("---")) {
      return "";
    }
    StringBuilder sb = new StringBuilder();
    for (int i = 1; i < lines.length; i++) {
      if (lines[i].trim().equals("---")) {
        break;
      }
      sb.append(lines[i]).append(System.lineSeparator());
    }
    return sb.toString();
  }

  /** frontmatter 中取 key: value（简单行解析，仅 name/description 用）。 */
  static String valueOf(String frontmatter, String key) {
    for (String line : frontmatter.split("\\R", -1)) {
      int idx = line.indexOf(':');
      if (idx > 0 && line.substring(0, idx).trim().equals(key)) {
        String value = line.substring(idx + 1).trim();
        return value.isEmpty() ? null : value;
      }
    }
    return null;
  }
}
