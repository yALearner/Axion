package com.axion.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.axion.core.ToolExecutor;
import com.axion.core.ToolResult;
import com.axion.storage.ToolInvocation;
import com.axion.storage.ToolInvocationRepository;
import com.axion.tool.builtin.ShellTools;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;

/**
 * 沙箱违规的审计落账 harness（007-sandbox FR-7，006 MemoryToolsTest 同款模式）——违规经 ToolExecutor 执行后 {@code
 * tool_invocations} 落 {@code success=false} + {@code error_message} 人能读懂（"命令不在白名单内: rm"）， 放行路径落
 * {@code success=true}。零新增审计逻辑的机器证据：SandboxViolationException 复用 ToolExecutor 既有 RuntimeException
 * 失败路径。
 */
class SandboxAuditTest {

  @Test
  @DisplayName("宪法 V / FR-7：白名单外命令经 ToolExecutor 执行后落账 success=false + error_message 可读")
  void auditWrittenOnSandboxViolation() throws Exception {
    ToolInvocationRepository auditRepo = mock(ToolInvocationRepository.class);
    ShellTools shellTool =
        new ShellTools(
            new WhitelistSandbox(
                Path.of("."),
                new FileSandboxProperties(List.of()),
                new ShellSandboxProperties(List.of("ls"), List.of()),
                new HttpSandboxProperties(List.of())),
            30_000,
            Set.of(),
            Path.of("."));
    ToolExecutor executor =
        new ToolExecutor(Map.of("shell", shellTool), auditRepo, new ObjectMapper());

    ToolResult result =
        executor.execute(
            "sess-1",
            new AssistantMessage.ToolCall(
                "call-1", "function", "shell", "{\"command\":\"rm -rf /tmp/x\"}"));

    assertThat(result.success()).isFalse();
    ArgumentCaptor<ToolInvocation> captor = ArgumentCaptor.forClass(ToolInvocation.class);
    verify(auditRepo).save(captor.capture());
    assertThat(captor.getValue().getSuccess()).isFalse();
    assertThat(captor.getValue().getErrorMessage()).contains("命令不在白名单内: rm");
  }

  @Test
  @DisplayName("宪法 V / FR-7：白名单内命令落账 success=true（放行与拒绝同一条审计路径）")
  void auditWrittenOnAllowedCommand() throws Exception {
    ToolInvocationRepository auditRepo = mock(ToolInvocationRepository.class);
    ShellTools shellTool =
        new ShellTools(
            new WhitelistSandbox(
                Path.of("."),
                new FileSandboxProperties(List.of()),
                new ShellSandboxProperties(List.of("echo"), List.of()),
                new HttpSandboxProperties(List.of())),
            30_000,
            Set.of(),
            Path.of("."));
    ToolExecutor executor =
        new ToolExecutor(Map.of("shell", shellTool), auditRepo, new ObjectMapper());

    ToolResult result =
        executor.execute(
            "sess-1",
            new AssistantMessage.ToolCall(
                "call-1", "function", "shell", "{\"command\":\"echo audit-ok\"}"));

    assertThat(result.success()).isTrue();
    ArgumentCaptor<ToolInvocation> captor = ArgumentCaptor.forClass(ToolInvocation.class);
    verify(auditRepo).save(captor.capture());
    assertThat(captor.getValue().getSuccess()).isTrue();
  }
}
