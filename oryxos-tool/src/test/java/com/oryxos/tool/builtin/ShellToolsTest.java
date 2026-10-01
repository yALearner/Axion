package com.oryxos.tool.builtin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oryxos.core.Profile;
import com.oryxos.core.ProfileContext;
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
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ShellTools 验收 harness——执行前必须先过 sandbox.enforce(SHELL_COMMAND)（坑十）；超时强制销毁 + 明确报错 （构造注入小超时，不等真实
 * 30s）；退出码非 0 → failure（输出进 errorMessage）；平台假设：bash（生产 Linux；本机 Git Bash）。
 */
class ShellToolsTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  @DisplayName("正常：执行命令返回标准输出")
  void executesCommand() {
    ShellTools tool = new ShellTools(mock(Sandbox.class), 30_000, Set.of(), Path.of("."));

    ToolResult result =
        tool.execute(objectMapper.createObjectNode().put("command", "echo hello-shell"));

    assertThat(result.success()).isTrue();
    assertThat(result.content()).contains("hello-shell");
  }

  @Test
  @DisplayName("坑十：执行前先过 enforce(SHELL_COMMAND, 命令)")
  void enforcesBeforeExecution() {
    Sandbox sandbox = mock(Sandbox.class);
    ShellTools tool = new ShellTools(sandbox, 30_000, Set.of(), Path.of("."));

    tool.execute(objectMapper.createObjectNode().put("command", "echo x"));

    verify(sandbox)
        .enforce(
            argThat(a -> a.type() == ActionType.SHELL_COMMAND && a.target().contains("echo x")));
  }

  @Test
  @DisplayName("违规：mock 拒绝时异常上抛、不执行")
  void violationBlocksExecution() {
    Sandbox sandbox = mock(Sandbox.class);
    org.mockito.Mockito.doThrow(new SandboxViolationException("命令不在白名单"))
        .when(sandbox)
        .enforce(any());
    ShellTools tool = new ShellTools(sandbox, 30_000, Set.of(), Path.of("."));

    assertThatThrownBy(() -> tool.execute(objectMapper.createObjectNode().put("command", "echo x")))
        .isInstanceOf(SandboxViolationException.class);
  }

  @Test
  @DisplayName("007 T014 接线回归：真实 WhitelistSandbox 白名单外命令——违规被拦且进程未执行（副作用文件不存在）")
  void whitelistSandboxBlocksCommand(@TempDir Path tmp) {
    WhitelistSandbox sandbox =
        new WhitelistSandbox(
            Path.of("."),
            new FileSandboxProperties(List.of()),
            new ShellSandboxProperties(List.of("ls"), List.of()),
            new HttpSandboxProperties(List.of()));
    ShellTools tool = new ShellTools(sandbox, 30_000, Set.of(), Path.of("."));
    Path marker = tmp.resolve("marker.txt");

    assertThatThrownBy(
            () -> tool.execute(objectMapper.createObjectNode().put("command", "touch " + marker)))
        .isInstanceOf(SandboxViolationException.class);
    assertThat(Files.exists(marker)).isFalse(); // 真进程没跑（不是 mock 拒绝，是白名单真拦）
  }

  @Test
  @DisplayName("退出码非 0：failure，输出进 errorMessage")
  void nonZeroExitFails() {
    ShellTools tool = new ShellTools(mock(Sandbox.class), 30_000, Set.of(), Path.of("."));

    ToolResult result =
        tool.execute(objectMapper.createObjectNode().put("command", "echo boom; exit 3"));

    assertThat(result.success()).isFalse();
    assertThat(result.errorMessage()).contains("boom");
  }

  @Test
  @DisplayName("超时：强制销毁进程 + 明确报错（构造注入 100ms 小超时）")
  void timeoutDestroysProcess() {
    ShellTools tool = new ShellTools(mock(Sandbox.class), 100, Set.of(), Path.of("."));

    ToolResult result = tool.execute(objectMapper.createObjectNode().put("command", "sleep 5"));

    assertThat(result.success()).isFalse();
    assertThat(result.errorMessage()).contains("超时");
  }

  @Test
  @DisplayName("坑六回归（011 FR-5）：解释器命令子进程 cwd = 当前 Agent 目录（相对路径按\"这个 Agent 的目录\"解析）")
  void interpreterCommandRunsInAgentDir(@TempDir Path workspace) throws Exception {
    Path agentDir = Files.createDirectories(workspace.resolve("agents").resolve("ops-agent"));
    Files.writeString(agentDir.resolve("AGENT.md"), "---\nname: ops-agent\n---\n");
    // 用 pwd 充当"解释器"验证 cwd 绑定（bindAgentWorkingDir 只认解释器集合成员，不关心真实解释器）
    ShellTools tool = new ShellTools(mock(Sandbox.class), 30_000, Set.of("pwd"), workspace);
    ProfileContext.set(
        new Profile(
            "ops-agent",
            null,
            new Profile.Identity(null, null),
            new Profile.ProviderRef("deepseek", null, null),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            new Profile.Settings(10, 20)));
    try {
      ToolResult result = tool.execute(objectMapper.createObjectNode().put("command", "pwd"));
      assertThat(result.success()).isTrue();
      assertThat(result.content()).contains("agents").contains("ops-agent");
    } finally {
      ProfileContext.clear();
    }
  }
}
