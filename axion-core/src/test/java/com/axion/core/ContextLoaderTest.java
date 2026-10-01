package com.axion.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

/**
 * ContextLoader 验收 harness——坑五（无缓存/缺失报错/WARN）、Skill 元数据注入（宪法 IV 软连接绑定）。
 *
 * <p>011 适配：load 注入 AGENT.md 正文（FR-2）——各测试须建 agents/ops-agent/AGENT.md（frontmatter-only 时正文为空，断言
 * 不变）；Skill 元数据注入改绑定路径（修订说明 ⑦）。
 */
class ContextLoaderTest {

  private static Profile profileWithBootstrap(List<String> bootstrap) {
    return new Profile(
        "ops-agent",
        null,
        new Profile.Identity("运维小欧", "你是一个助手"),
        new Profile.ProviderRef("deepseek", null, null),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        bootstrap,
        new Profile.Settings(10, 20));
  }

  /** 建 agents/ops-agent/AGENT.md（frontmatter-only 且无尾换行：正文为空——旧断言"context 为空"保持成立）。 */
  private static void writeAgent(Path workspace) throws Exception {
    Path agentDir = Files.createDirectories(workspace.resolve("agents").resolve("ops-agent"));
    Files.writeString(agentDir.resolve("AGENT.md"), "---\nname: ops-agent\n---");
  }

  @Test
  @DisplayName("坑五回归：改文件后下一次 load 立即读到新内容（无缓存）")
  void fileChangesVisibleOnNextLoad(@TempDir Path workspace) throws Exception {
    writeAgent(workspace);
    Path agentsMd = workspace.resolve("AGENTS.md");
    Files.writeString(agentsMd, "版本一");
    ContextLoader loader = new ContextLoader(workspace);

    assertThat(loader.load(profileWithBootstrap(List.of("AGENTS.md")))).contains("版本一");

    Files.writeString(agentsMd, "版本二");
    assertThat(loader.load(profileWithBootstrap(List.of("AGENTS.md")))).contains("版本二");
  }

  @Test
  @DisplayName("显式引用的文件缺失报错（不静默跳过）")
  void missingExplicitReferenceThrows(@TempDir Path workspace) throws Exception {
    writeAgent(workspace);
    Files.writeString(workspace.resolve("AGENTS.md"), "ok");
    ContextLoader loader = new ContextLoader(workspace);

    assertThatThrownBy(() -> loader.load(profileWithBootstrap(List.of("AGENTS.md", "NO_SUCH.md"))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("NO_SUCH.md");
  }

  @Test
  @DisplayName("Bootstrap 未配置时至少 WARN（坑五：人格悄悄丢了这类软故障不许静默）")
  void missingBootstrapWarns(@TempDir Path workspace) throws Exception {
    writeAgent(workspace);
    ContextLoader loader = new ContextLoader(workspace);
    Logger logger = (Logger) LoggerFactory.getLogger(ContextLoader.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      String context = loader.load(profileWithBootstrap(List.of()));
      assertThat(context).isEmpty(); // 不报错，但必须有 WARN（正文为空：frontmatter-only fixture）
    } finally {
      logger.detachAppender(appender);
    }
    assertThat(appender.list)
        .anyMatch(
            event ->
                event.getLevel() == Level.WARN
                    && event.getFormattedMessage().contains("Bootstrap"));
  }

  @Test
  @DisplayName("已绑定 Skill 的元数据注入：name + description + 绑定路径（软连接绑定真相源，修订说明 ⑦）")
  void skillMetadataInjectedFromBinding(@TempDir Path workspace) throws Exception {
    writeAgent(workspace);
    Path skillDir = workspace.resolve("skills").resolve("weather");
    Files.createDirectories(skillDir);
    Files.writeString(
        skillDir.resolve("SKILL.md"), "---\nname: weather\n" + "description: 查询天气的技能\n---\n正文不预载");
    Path bindingDir = workspace.resolve("agents").resolve("ops-agent").resolve("skills");
    Files.createDirectories(bindingDir);
    createBinding(bindingDir.resolve("weather"), workspace.resolve("skills").resolve("weather"));

    String context = new ContextLoader(workspace).load(profileWithBootstrap(List.of()));

    Path bindingPath = bindingDir.resolve("weather").toAbsolutePath().normalize();
    assertThat(context)
        .contains("weather")
        .contains("查询天气的技能")
        .contains(bindingPath.resolve("SKILL.md").toString()); // 绑定路径（011 修订说明 ⑦）
    assertThat(context).doesNotContain(skillDir.toAbsolutePath().resolve("SKILL.md").toString());
  }

  @Test
  @DisplayName("Skill 绑定目标逃逸公共 Skill 根 → 报错（不静默放行）")
  void bindingOutsideSkillRootThrows(@TempDir Path workspace) throws Exception {
    writeAgent(workspace);
    Path outside = Files.createDirectories(workspace.resolve("agents").resolve("evil-target"));
    Files.writeString(outside.resolve("SKILL.md"), "---\nname: evil\n---\n");
    Path bindingDir = workspace.resolve("agents").resolve("ops-agent").resolve("skills");
    Files.createDirectories(bindingDir);
    createBinding(bindingDir.resolve("evil"), outside);

    assertThatThrownBy(() -> new ContextLoader(workspace).load(profileWithBootstrap(List.of())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Skill");
  }

  @Test
  @DisplayName("skills 目录不存在 → 跳过不报错")
  void missingSkillsDirIsSkipped(@TempDir Path workspace) throws Exception {
    writeAgent(workspace);
    String context = new ContextLoader(workspace).load(profileWithBootstrap(List.of()));
    assertThat(context).isEmpty(); // 正文为空 + 无 bootstrap + 无绑定
  }

  /**
   * 创建目录绑定：POSIX 用符号链接；Windows 无符号链接特权时用 junction（mklink /J，目录挂载点重解析点， Java 侧以 isOther()
   * 识别）——两者都是"文件系统级链接"，语义一致。
   */
  static void createBinding(Path link, Path target) throws Exception {
    if (System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")) {
      Process process =
          new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString())
              .redirectErrorStream(true)
              .start();
      int exit = process.waitFor();
      if (exit != 0) {
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        throw new IllegalStateException("junction 创建失败: " + output);
      }
    } else {
      Files.createSymbolicLink(link, link.getParent().relativize(target));
    }
  }
}
