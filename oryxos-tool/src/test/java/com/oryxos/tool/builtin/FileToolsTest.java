package com.oryxos.tool.builtin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oryxos.core.ToolResult;
import com.oryxos.tool.ActionType;
import com.oryxos.tool.FileSandboxProperties;
import com.oryxos.tool.HttpSandboxProperties;
import com.oryxos.tool.Sandbox;
import com.oryxos.tool.SandboxViolationException;
import com.oryxos.tool.ShellSandboxProperties;
import com.oryxos.tool.WhitelistSandbox;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 内置文件工具验收 harness（三件）——各"正常跑通 + 越界被拦"两条（课件 §四模板）：坑十执行前必须先过 sandbox.enforce(FILE_READ/FILE_WRITE)
 * 校验；违规时 IO 零发生（write 违规后文件不存在是可观察证据）； write_file 覆盖语义与父目录缺失报错；read_file 不存在报错；list_dir 非目录报错。
 */
class FileToolsTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  @DisplayName("read_file：正常读取文件内容")
  void readFileReturnsContent(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("a.txt");
    Files.writeString(file, "hello 文件");
    Sandbox sandbox = mock(Sandbox.class);
    ReadFileTool tool = new ReadFileTool(sandbox);

    ToolResult result = tool.execute(objectMapper.createObjectNode().put("path", file.toString()));

    assertThat(result.success()).isTrue();
    assertThat(result.content()).isEqualTo("hello 文件");
    verify(sandbox)
        .enforce(
            argThat(a -> a.type() == ActionType.FILE_READ && a.target().equals(file.toString())));
  }

  @Test
  @DisplayName("read_file：文件不存在明确报错（不静默）")
  void readFileMissingThrows(@TempDir Path tmp) {
    ReadFileTool tool = new ReadFileTool(mock(Sandbox.class));

    ToolResult result =
        tool.execute(
            objectMapper.createObjectNode().put("path", tmp.resolve("none.txt").toString()));

    assertThat(result.success()).isFalse();
    assertThat(result.errorMessage()).contains("none.txt");
  }

  @Test
  @DisplayName("read_file：违规（mock 拒绝）时异常上抛、不读取")
  void readFileViolationBlocks(@TempDir Path tmp) {
    Sandbox sandbox = mock(Sandbox.class);
    org.mockito.Mockito.doThrow(new SandboxViolationException("路径不在白名单"))
        .when(sandbox)
        .enforce(org.mockito.ArgumentMatchers.any());
    ReadFileTool tool = new ReadFileTool(sandbox);

    assertThatThrownBy(
            () ->
                tool.execute(
                    objectMapper.createObjectNode().put("path", tmp.resolve("a.txt").toString())))
        .isInstanceOf(SandboxViolationException.class);
  }

  @Test
  @DisplayName("write_file：正常写入 + 覆盖已存在文件")
  void writeFileWritesAndOverwrites(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("b.txt");
    Files.writeString(file, "old");
    WriteFileTool tool = new WriteFileTool(mock(Sandbox.class));

    ToolResult result =
        tool.execute(
            objectMapper.createObjectNode().put("path", file.toString()).put("content", "new"));

    assertThat(result.success()).isTrue();
    assertThat(result.content()).contains("已写入");
    assertThat(Files.readString(file)).isEqualTo("new");
  }

  @Test
  @DisplayName("write_file：父目录不存在明确报错、不递归建目录")
  void writeFileMissingParentThrows(@TempDir Path tmp) {
    WriteFileTool tool = new WriteFileTool(mock(Sandbox.class));

    ToolResult result =
        tool.execute(
            objectMapper
                .createObjectNode()
                .put("path", tmp.resolve("no-dir").resolve("c.txt").toString())
                .put("content", "x"));

    assertThat(result.success()).isFalse();
    assertThat(result.errorMessage()).contains("父目录不存在");
  }

  @Test
  @DisplayName("write_file：违规时 IO 零发生——文件不存在")
  void writeFileViolationZeroIo(@TempDir Path tmp) {
    Sandbox sandbox = mock(Sandbox.class);
    org.mockito.Mockito.doThrow(new SandboxViolationException("路径不在白名单"))
        .when(sandbox)
        .enforce(org.mockito.ArgumentMatchers.any());
    WriteFileTool tool = new WriteFileTool(sandbox);
    Path file = tmp.resolve("blocked.txt");

    assertThatThrownBy(
            () ->
                tool.execute(
                    objectMapper
                        .createObjectNode()
                        .put("path", file.toString())
                        .put("content", "x")))
        .isInstanceOf(SandboxViolationException.class);
    assertThat(Files.exists(file)).isFalse(); // 坑十：校验在前，IO 零发生
  }

  @Test
  @DisplayName("007 T014 接线回归：真实 WhitelistSandbox 白名单外 write_file——违规被拦且文件未创建")
  void whitelistSandboxBlocksWriteOutsideRoot(@TempDir Path tmp) {
    WhitelistSandbox sandbox =
        new WhitelistSandbox(
            Path.of("."),
            new FileSandboxProperties(List.of("/workspace")),
            new ShellSandboxProperties(List.of(), List.of()),
            new HttpSandboxProperties(List.of()));
    WriteFileTool tool = new WriteFileTool(sandbox);
    Path file = tmp.resolve("blocked.txt"); // @TempDir 路径在白名单外

    assertThatThrownBy(
            () ->
                tool.execute(
                    objectMapper
                        .createObjectNode()
                        .put("path", file.toString())
                        .put("content", "x")))
        .isInstanceOf(SandboxViolationException.class);
    assertThat(Files.exists(file)).isFalse(); // 真 IO 没发生（不是 mock 拒绝，是白名单真拦）
  }

  @Test
  @DisplayName("list_dir：正常列出条目（名 + 类型）")
  void listDirListsEntries(@TempDir Path tmp) throws Exception {
    Files.writeString(tmp.resolve("f.txt"), "x");
    Files.createDirectory(tmp.resolve("sub"));
    ListDirTool tool = new ListDirTool(mock(Sandbox.class));

    ToolResult result = tool.execute(objectMapper.createObjectNode().put("path", tmp.toString()));

    assertThat(result.success()).isTrue();
    assertThat(result.content()).contains("f.txt", "sub");
  }

  @Test
  @DisplayName("list_dir：非目录明确报错")
  void listDirOnFileThrows(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("f.txt");
    Files.writeString(file, "x");
    ListDirTool tool = new ListDirTool(mock(Sandbox.class));

    ToolResult result = tool.execute(objectMapper.createObjectNode().put("path", file.toString()));

    assertThat(result.success()).isFalse();
  }
}
