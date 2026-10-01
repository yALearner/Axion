package com.oryxos.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oryxos.storage.ToolInvocation;
import com.oryxos.storage.ToolInvocationRepository;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;

/** ToolExecutor 验收 harness——按名执行、成败都写审计、retryable 回传、无内部自动重试。 */
class ToolExecutorTest {

  private final ToolInvocationRepository audit = mock(ToolInvocationRepository.class);
  private final ObjectMapper mapper = new ObjectMapper();

  private final OryxTool httpGet = mock(OryxTool.class);

  private ToolExecutor executor() {
    return new ToolExecutor(Map.of("http_get", httpGet), audit, mapper);
  }

  private static AssistantMessage.ToolCall toolCall(String name) {
    return new AssistantMessage.ToolCall("call-1", "function", name, "{\"url\":\"http://x\"}");
  }

  @Test
  @DisplayName("按名找到工具并执行，结果原样返回，审计记 success=true")
  void executesToolByNameAndAuditsSuccess() throws Exception {
    when(httpGet.execute(any(JsonNode.class))).thenReturn(ToolResult.success("22°C"));

    ToolResult result = executor().execute("s-1", toolCall("http_get"));

    assertThat(result.success()).isTrue();
    assertThat(result.content()).isEqualTo("22°C");
    ArgumentCaptor<ToolInvocation> captor = ArgumentCaptor.forClass(ToolInvocation.class);
    verify(audit).save(captor.capture());
    ToolInvocation recorded = captor.getValue();
    assertThat(recorded.getSuccess()).isTrue();
    assertThat(recorded.getSessionId()).isEqualTo("s-1");
    assertThat(recorded.getToolName()).isEqualTo("http_get");
    assertThat(recorded.getInputJson()).isEqualTo("{\"url\":\"http://x\"}");
    assertThat(recorded.getResultJson()).contains("22°C");
    assertThat(recorded.getErrorMessage()).isNull();
    assertThat(recorded.getDurationMs()).isNotNull();
  }

  @Test
  @DisplayName("未知工具名返回失败结果（不可重试）并记审计 success=false")
  void unknownToolNameReturnsFailureAndAudits() {
    ToolResult result = executor().execute("s-1", toolCall("no_such_tool"));

    assertThat(result.success()).isFalse();
    assertThat(result.errorMessage()).contains("no_such_tool");
    assertThat(result.retryable()).isFalse();
    ArgumentCaptor<ToolInvocation> captor = ArgumentCaptor.forClass(ToolInvocation.class);
    verify(audit).save(captor.capture());
    assertThat(captor.getValue().getSuccess()).isFalse();
    assertThat(captor.getValue().getErrorMessage()).contains("no_such_tool");
  }

  @Test
  @DisplayName("坑七：工具返回失败结果时审计 success=false 带原因，retryable 原样回传")
  void toolFailureResultAuditsWithReasonAndKeepsRetryable() {
    when(httpGet.execute(any(JsonNode.class))).thenReturn(ToolResult.failure("上游服务超时", true));

    ToolResult result = executor().execute("s-1", toolCall("http_get"));

    assertThat(result.success()).isFalse();
    assertThat(result.errorMessage()).isEqualTo("上游服务超时");
    assertThat(result.retryable()).isTrue();
    ArgumentCaptor<ToolInvocation> captor = ArgumentCaptor.forClass(ToolInvocation.class);
    verify(audit).save(captor.capture());
    assertThat(captor.getValue().getSuccess()).isFalse();
    assertThat(captor.getValue().getErrorMessage()).isEqualTo("上游服务超时");
  }

  @Test
  @DisplayName("坑七：工具抛异常不吞——审计记失败与原因，返回失败结果而不是上抛")
  void toolThrowingExceptionIsRecordedNotSwallowed() {
    when(httpGet.execute(any(JsonNode.class))).thenThrow(new RuntimeException("connect refused"));

    ToolResult result = executor().execute("s-1", toolCall("http_get"));

    assertThat(result.success()).isFalse();
    assertThat(result.errorMessage()).contains("connect refused");
    ArgumentCaptor<ToolInvocation> captor = ArgumentCaptor.forClass(ToolInvocation.class);
    verify(audit).save(captor.capture());
    assertThat(captor.getValue().getSuccess()).isFalse();
    assertThat(captor.getValue().getErrorMessage()).contains("connect refused");
  }

  @Test
  @DisplayName("无内部自动重试：任何路径下工具恰好执行一次")
  void noInternalRetry() throws Exception {
    when(httpGet.execute(any(JsonNode.class))).thenThrow(new RuntimeException("boom"));

    executor().execute("s-1", toolCall("http_get"));

    verify(httpGet).execute(any(JsonNode.class)); // 默认 times(1)
  }

  @Test
  @DisplayName("失败路径：审计在执行抛异常之后、返回结果之前先落账")
  void failureAuditsBeforeResultReturned() throws Exception {
    java.util.List<String> events = new java.util.ArrayList<>();
    when(httpGet.execute(any(JsonNode.class)))
        .thenAnswer(
            inv -> {
              events.add("execute");
              throw new RuntimeException("boom");
            });
    when(audit.save(any(ToolInvocation.class)))
        .thenAnswer(
            inv -> {
              events.add("audit");
              return null;
            });

    ToolResult result = executor().execute("s-1", toolCall("http_get"));

    assertThat(result.success()).isFalse();
    assertThat(events).containsExactly("execute", "audit");
  }
}
