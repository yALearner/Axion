package com.axion.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** AgentStore 目录读写 harness（012 FR-4）——写落点与手工丢目录一致、归档不物理删、回滚删除、脚手架四件。 */
class AgentStoreTest {

  @Test
  @DisplayName("write 落 .axion/agents/<name>/AGENT.md——与手工丢目录格式一模一样")
  void writeLandsAgentMarkdownUnderAgentsRoot(@TempDir Path tempDir) throws Exception {
    AgentStore store = new AgentStore(tempDir);

    store.write("ops-agent", "---\nname: ops-agent\n---\n正文");

    assertThat(Files.readString(tempDir.resolve("agents").resolve("ops-agent").resolve("AGENT.md")))
        .isEqualTo("---\nname: ops-agent\n---\n正文");
  }

  @Test
  @DisplayName("archive 移入 .axion/archive/ 不物理删（可追溯）")
  void archiveMovesDirectoryNotDeletes(@TempDir Path tempDir) throws Exception {
    AgentStore store = new AgentStore(tempDir);
    store.write("ops-agent", "AGENT.md 内容");

    store.archive("ops-agent");

    assertThat(Files.exists(tempDir.resolve("archive").resolve("ops-agent").resolve("AGENT.md")))
        .isTrue();
    assertThat(Files.exists(tempDir.resolve("agents").resolve("ops-agent"))).isFalse();
  }

  @Test
  @DisplayName("archive 目标不存在 → 异常上抛（delete 顺序钉死的前置：归档前索引已移出）")
  void archiveMissingDirThrows(@TempDir Path tempDir) {
    AgentStore store = new AgentStore(tempDir);

    assertThatThrownBy(() -> store.archive("ghost")).isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("delete 递归删除（坑十一回滚用）；不存在时幂等无异常")
  void deleteRemovesRecursivelyAndIsIdempotent(@TempDir Path tempDir) throws Exception {
    AgentStore store = new AgentStore(tempDir);
    Path dir = store.write("half", "AGENT.md");
    Files.createDirectories(dir.resolve("scripts"));
    Files.writeString(dir.resolve("scripts").resolve("x.py"), "print(1)");

    store.delete(dir);
    assertThat(Files.exists(dir)).isFalse();

    store.delete(dir); // 幂等
  }

  @Test
  @DisplayName("writeAll 写一组文件（相对路径为根）+ 越界相对路径拒绝")
  void writeAllWritesFileMapAndRejectsEscapes(@TempDir Path tempDir) throws Exception {
    AgentStore store = new AgentStore(tempDir);

    store.writeAll("ops-agent", Map.of("AGENT.md", "md", "scripts/x.py", "print(1)"));

    assertThat(Files.readString(tempDir.resolve("agents").resolve("ops-agent").resolve("AGENT.md")))
        .isEqualTo("md");
    assertThat(
            Files.readString(
                tempDir.resolve("agents").resolve("ops-agent").resolve("scripts").resolve("x.py")))
        .isEqualTo("print(1)");

    assertThatThrownBy(() -> store.writeAll("ops-agent", Map.of("../evil.md", "x")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName(
      "脚手架四件（R6）：AGENT.md frontmatter 完整 + scripts//skills/ 占位 + REFERENCE.md；tools/schedules"
          + " 留空（最小权限）")
  void scaffoldProducesFourFilesWithMinimalPermissions(@TempDir Path tempDir) {
    AgentStore store = new AgentStore(tempDir);
    Map<String, String> files = store.scaffold("ops-agent", "运维助手", "deepseek", "deepseek-chat");

    assertThat(files.keySet())
        .containsExactlyInAnyOrder("AGENT.md", "scripts/.keep", "skills/.keep", "REFERENCE.md");
    String agentMd = files.get("AGENT.md");
    assertThat(agentMd).contains("name: ops-agent").contains("description: 运维助手");
    assertThat(agentMd).contains("name: deepseek").contains("model: deepseek-chat");
    assertThat(agentMd).contains("tools: []"); // 最小权限：默认无工具
    assertThat(agentMd).doesNotContain("schedules:"); // 默认无定时
  }

  @Test
  @DisplayName("脚手架 model 留空则省略 model 键（坑十五：不发 model=null）")
  void scaffoldOmitsBlankModel(@TempDir Path tempDir) {
    AgentStore store = new AgentStore(tempDir);
    Map<String, String> files = store.scaffold("ops-agent", "运维助手", "deepseek", null);

    assertThat(files.get("AGENT.md")).doesNotContain("model:");
  }
}
