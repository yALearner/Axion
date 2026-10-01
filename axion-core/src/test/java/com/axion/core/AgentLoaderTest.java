package com.axion.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * AgentLoader 验收 harness（003 ProfileLoaderTest 更名扩展，011
 * FR-1）——frontmatter/正文拆分、资源认出、报错点名（坑一/坑二/坑八）。
 */
class AgentLoaderTest {

  @Test
  @DisplayName("坑一回归：正文原样拆出、不做二次加工；frontmatter 不含正文、正文不含 frontmatter")
  void bodySplitVerbatim(@TempDir Path tmp) throws Exception {
    Path agentDir = Files.createDirectories(tmp.resolve("split-agent"));
    Files.writeString(
        agentDir.resolve("AGENT.md"),
        """
        ---
        name: split-agent
        provider:
          name: deepseek
        ---
          **正文第一行有前导空格**

        正文第三行，含 provider: 字样与前后空行。

        """);

    AgentLoader loader = new AgentLoader();
    AgentLoader.FrontMatterAndBody split =
        AgentLoader.split(
            Files.readString(agentDir.resolve("AGENT.md")), agentDir.resolve("AGENT.md"));

    assertThat(split.frontmatter()).containsEntry("name", "split-agent");
    assertThat(split.body()).startsWith("\n  **正文第一行有前导空格**");
    assertThat(split.body()).contains("正文第三行，含 provider: 字样");
    assertThat(split.body()).endsWith("\n\n"); // 原样：不 trim、不二次加工

    assertThat(loader.loadBody(agentDir)).isEqualTo(split.body());
  }

  @Test
  @DisplayName("认出资源：scripts/ / skills/ / REFERENCE.md 按存在性返回")
  void detectResources(@TempDir Path tmp) throws Exception {
    Path agentDir = Files.createDirectories(tmp.resolve("res-agent"));
    Files.writeString(
        agentDir.resolve("AGENT.md"),
        "---\nname: res-agent\nprovider:\n  name: deepseek\n---\n正文\n");
    assertThat(new AgentLoader().detectResources(agentDir)).isEmpty();

    Files.createDirectories(agentDir.resolve("scripts"));
    Files.createDirectories(agentDir.resolve("skills"));
    Files.writeString(agentDir.resolve("REFERENCE.md"), "参考");

    assertThat(new AgentLoader().detectResources(agentDir))
        .containsExactlyInAnyOrder("scripts", "skills", "REFERENCE.md");
  }

  @Test
  @DisplayName("坑二回归：缺 name 报错点名文件与键")
  void missingNameFailsWithClearError(@TempDir Path tmp) throws Exception {
    Path agentDir = Files.createDirectories(tmp.resolve("no-name-agent"));
    Path agentFile = agentDir.resolve("AGENT.md");
    Files.writeString(agentFile, "---\nprovider:\n  name: deepseek\n---\n正文\n");

    assertThatThrownBy(() -> new AgentLoader().deriveProfile(agentDir, Set.of("deepseek")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("name")
        .hasMessageContaining(agentFile.toString());
  }

  @Test
  @DisplayName("坑八回归：frontmatter name 与目录名不一致 → 派生期报错点名（不晚失败）")
  void nameMismatchWithDirFails(@TempDir Path tmp) throws Exception {
    Path agentDir = Files.createDirectories(tmp.resolve("dir-name"));
    Files.writeString(
        agentDir.resolve("AGENT.md"),
        "---\nname: other-name\nprovider:\n  name: deepseek\n---\n正文\n");

    assertThatThrownBy(() -> new AgentLoader().deriveProfile(agentDir, Set.of("deepseek")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不一致")
        .hasMessageContaining("dir-name")
        .hasMessageContaining("other-name");
  }

  @Test
  @DisplayName("缺 AGENT.md：loadBody 报错点名")
  void missingAgentMdLoadBodyFails(@TempDir Path tmp) throws Exception {
    Path agentDir = Files.createDirectories(tmp.resolve("empty-agent"));

    assertThatThrownBy(() -> new AgentLoader().loadBody(agentDir))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("empty-agent");
  }

  @Test
  @DisplayName("引用不存在的 provider：报错清晰（含 provider 名）")
  void missingProviderFailsWithClearError(@TempDir Path tmp) throws Exception {
    Path agentDir = Files.createDirectories(tmp.resolve("bad-agent"));
    Files.writeString(
        agentDir.resolve("AGENT.md"),
        """
        ---
        name: bad-agent
        provider:
          name: nonexistent
        ---
        正文
        """);

    assertThatThrownBy(() -> new AgentLoader().deriveProfile(agentDir, Set.of("deepseek")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("nonexistent");
  }

  @Test
  @DisplayName("⑦c：schedules 条目缺 id 启动报错（不静默、不派生兜底）")
  void scheduleWithoutIdFailsWithClearError(@TempDir Path tmp) throws Exception {
    Path agentDir = Files.createDirectories(tmp.resolve("no-id-agent"));
    Files.writeString(
        agentDir.resolve("AGENT.md"),
        """
        ---
        name: no-id-agent
        provider:
          name: deepseek
        schedules:
          - cron: "0 8 * * *"
            message: 早安
        ---
        正文
        """);

    assertThatThrownBy(() -> new AgentLoader().deriveProfile(agentDir, Set.of("deepseek")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("no-id-agent")
        .hasMessageContaining("id");
  }

  @Test
  @DisplayName("坏文件不阻断其余加载：只注册合法 Agent")
  void badFileDoesNotBlockOthers(@TempDir Path tmp) throws Exception {
    Path good = Files.createDirectories(tmp.resolve("good-agent"));
    Files.writeString(
        good.resolve("AGENT.md"), "---\nname: good-agent\nprovider:\n  name: deepseek\n---\n正文\n");
    Path bad = Files.createDirectories(tmp.resolve("bad-agent"));
    Files.writeString(
        bad.resolve("AGENT.md"), "---\nname: bad-agent\nprovider:\n  name: nope\n---\n正文\n");

    Map<String, Profile> loaded = new AgentLoader().loadAll(tmp, Set.of("deepseek"));

    assertThat(loaded).containsOnlyKeys("good-agent");
  }

  @Test
  @DisplayName("缺 AGENT.md 的目录：跳过不阻断")
  void missingAgentMdIsSkipped(@TempDir Path tmp) throws Exception {
    Path good = Files.createDirectories(tmp.resolve("good-agent"));
    Files.writeString(
        good.resolve("AGENT.md"), "---\nname: good-agent\nprovider:\n  name: deepseek\n---\n正文\n");
    Files.createDirectories(tmp.resolve("empty-dir"));

    Map<String, Profile> loaded = new AgentLoader().loadAll(tmp, Set.of("deepseek"));

    assertThat(loaded).containsOnlyKeys("good-agent");
  }
}
