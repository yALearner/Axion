package com.axion.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.axion.storage.SessionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

/** ReActLoop 验收 harness——多轮循环、累积、坑一死循环兜底、坑六执行权唯一。 */
class ReActLoopTest {

  @org.junit.jupiter.api.io.TempDir Path workspace;

  private final LlmGateway gateway = mock(LlmGateway.class);
  private final ToolExecutor toolExecutor = mock(ToolExecutor.class);

  /** 003 起 SessionManager 为 JPA 版（构造注入仓储）；测试用 mock 仓储 + 真 ObjectMapper。 */
  private SessionManager newSessionManager() {
    SessionRepository repository = mock(SessionRepository.class);
    when(repository.findById(anyString())).thenReturn(java.util.Optional.empty());
    return new SessionManager(repository, new ObjectMapper());
  }

  private ReActLoop loop() {
    // 真实 PromptBuilder（骨架期无历史注入也无妨——ReActLoop 只关心拿到 Prompt）；
    // 011 改造点适配：ContextLoader 现注入 AGENT.md 正文（FR-2）——工作区从 cwd 改为 @TempDir 并建
    // test-agent 目录（frontmatter-only 且无尾换行：正文为空）；MemoryService mock（002 改造点：构造器新增参数）
    try {
      java.nio.file.Path agentDir =
          java.nio.file.Files.createDirectories(workspace.resolve("agents").resolve("test-agent"));
      java.nio.file.Files.writeString(agentDir.resolve("AGENT.md"), "---\nname: test-agent\n---");
    } catch (java.io.IOException e) {
      throw new IllegalStateException("fixture 创建失败", e);
    }
    MemoryService memoryService = mock(MemoryService.class);
    when(memoryService.buildContext(org.mockito.ArgumentMatchers.any(Session.class)))
        .thenReturn("");
    PromptBuilder promptBuilder =
        new PromptBuilder(
            new ContextLoader(workspace),
            new ToolSchemaAdapter(new ObjectMapper()),
            Map.of(),
            memoryService);
    return new ReActLoop(gateway, promptBuilder, toolExecutor);
  }

  private static Profile profileWith(int maxIterations) {
    return new Profile(
        "test-agent",
        null,
        new Profile.Identity(null, "你是一个助手"),
        new Profile.ProviderRef("deepseek", null, null),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        new Profile.Settings(maxIterations, 20));
  }

  private static ChatResponse textResponse(String text) {
    return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
  }

  private static ChatResponse toolCallResponse() {
    AssistantMessage.ToolCall call =
        new AssistantMessage.ToolCall("call-1", "function", "http_get", "{\"url\":\"wttr.in\"}");
    AssistantMessage message =
        AssistantMessage.builder().content("").toolCalls(List.of(call)).build();
    return new ChatResponse(List.of(new Generation(message)));
  }

  @Test
  @DisplayName("无 tool 调用：一轮收尾并返回最终答复")
  void singleRoundWhenNoToolCalls() {
    when(gateway.chat(anyString(), any(), any())).thenReturn(textResponse("今天适合穿薄外套"));

    Session session = newSessionManager().getOrCreate("cli", "alice", "test-agent");
    String reply = loop().run(session, "查天气", profileWith(10));

    assertThat(reply).isEqualTo("今天适合穿薄外套");
    verify(gateway, times(1)).chat(anyString(), any(), any());
  }

  @Test
  @DisplayName("有 tool 调用：执行后回填进下一轮，直到模型给出最终答复")
  void toolCallsExecutedAndFedBackUntilFinalAnswer() {
    when(gateway.chat(anyString(), any(), any()))
        .thenReturn(toolCallResponse())
        .thenReturn(textResponse("建议穿外套"));
    when(toolExecutor.execute(anyString(), any())).thenReturn(ToolResult.success("北京 22°C"));

    Session session = newSessionManager().getOrCreate("cli", "alice", "test-agent");
    String reply = loop().run(session, "查天气", profileWith(10));

    assertThat(reply).isEqualTo("建议穿外套");
    verify(gateway, times(2)).chat(anyString(), any(), any());
    verify(toolExecutor, times(1)).execute(anyString(), any());
  }

  @Test
  @DisplayName("坑三回归：每轮响应与工具结果按序累积进 Session")
  void responsesAndToolResultsAccumulateInOrder() {
    when(gateway.chat(anyString(), any(), any()))
        .thenReturn(toolCallResponse())
        .thenReturn(textResponse("最终答复"));
    when(toolExecutor.execute(anyString(), any())).thenReturn(ToolResult.success("北京 22°C"));

    Session session = newSessionManager().getOrCreate("cli", "alice", "test-agent");
    loop().run(session, "查天气", profileWith(10));

    List<Message> messages = session.messages();
    assertThat(messages).hasSize(4);
    assertThat(messages.get(0).role()).isEqualTo(Message.MessageRole.USER);
    assertThat(messages.get(1).role()).isEqualTo(Message.MessageRole.ASSISTANT);
    assertThat(messages.get(1).toolCalls()).hasSize(1);
    assertThat(messages.get(1).toolCalls().get(0).name()).isEqualTo("http_get");
    assertThat(messages.get(2).role()).isEqualTo(Message.MessageRole.TOOL);
    assertThat(messages.get(2).toolResults()).hasSize(1);
    assertThat(messages.get(2).toolResults().get(0).toolCallId()).isEqualTo("call-1");
    assertThat(messages.get(2).content()).contains("北京 22°C");
    assertThat(messages.get(3).role()).isEqualTo(Message.MessageRole.ASSISTANT);
    assertThat(messages.get(3).content()).isEqualTo("最终答复");
  }

  @Test
  @DisplayName("坑一回归：模型一直要调工具 → 恰好最大轮数强制停止")
  void forcedStopAtMaxIterationsWhenModelNeverConverges() {
    when(gateway.chat(anyString(), any(), any())).thenReturn(toolCallResponse());
    when(toolExecutor.execute(anyString(), any())).thenReturn(ToolResult.success("ok"));

    Session session = newSessionManager().getOrCreate("cli", "alice", "test-agent");
    String reply = loop().run(session, "查天气", profileWith(10));

    verify(gateway, times(10)).chat(anyString(), any(), any());
    verify(toolExecutor, times(10)).execute(anyString(), any());
    assertThat(reply).contains("达到最大轮数");
  }

  @Test
  @DisplayName("坑六回归：工具请求经 ToolExecutor 恰好执行一次（无框架自动执行路径）")
  void toolCallExecutedExactlyOnceThroughToolExecutor() {
    when(gateway.chat(anyString(), any(), any())).thenReturn(textResponse("直接答复"));

    loop()
        .run(newSessionManager().getOrCreate("cli", "alice", "test-agent"), "hi", profileWith(10));

    verify(toolExecutor, times(0)).execute(anyString(), any());
  }

  @Test
  @DisplayName("每轮模型调用携带会话标识（llm_calls 按 session 关联审计）")
  void eachLlmCallCarriesSessionId() {
    when(gateway.chat(anyString(), any(), any())).thenReturn(textResponse("ok"));

    Session session = newSessionManager().getOrCreate("cli", "alice", "test-agent");
    loop().run(session, "hi", profileWith(10));

    verify(gateway).chat(eq("cli|alice|test-agent"), any(), any(Prompt.class));
  }

  @Test
  @DisplayName("纠偏回归：工具失败（含 retryable 提示）进 Session，模型下一轮换工具成功，最终答复基于纠偏后结果")
  void failureResultFedBackAndModelSelfCorrects() {
    when(gateway.chat(anyString(), any(), any()))
        .thenReturn(toolCallResponse()) // 第 1 轮：模型决定调 http_get
        .thenReturn(textResponse("网络不可用，已改从本地缓存查询，建议穿外套")); // 第 2 轮：看到失败后直接给最终答复
    when(toolExecutor.execute(anyString(), any()))
        .thenReturn(ToolResult.failure("网络错误", true)); // 失败结果带 retryable 回传

    Session session = newSessionManager().getOrCreate("cli", "alice", "test-agent");
    String reply = loop().run(session, "查天气", profileWith(10));

    assertThat(reply).isEqualTo("网络不可用，已改从本地缓存查询，建议穿外套");
    // 失败结果完整进 Session：错误原因 + retryable 提示都在 TOOL 消息里（下一轮上下文由此而来）
    Message toolMessage = session.messages().get(2);
    assertThat(toolMessage.role()).isEqualTo(Message.MessageRole.TOOL);
    assertThat(toolMessage.content()).contains("网络错误");
    assertThat(toolMessage.content()).contains("（可重试）");
    // 执行层无内部自动重试：失败的 http_get 恰好执行一次
    verify(toolExecutor, times(1)).execute(anyString(), any());
  }
}
