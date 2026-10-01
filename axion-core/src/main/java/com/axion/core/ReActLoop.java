package com.oryxos.core;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * ReAct 循环引擎——OryxOS 最核心的一段代码（宪法 I：自实现，不触发 Spring AI 的 Agent 抽象）。
 *
 * <p>七步：① 用户消息追加到 Session ② 组装 Prompt ③ 调 {@link LlmGateway}（携带 session.id()，llm_calls 按会话 关联审计）④
 * 无工具调用 → 返回最终答复 ⑤ 有 → 逐个交 {@link ToolExecutor} 执行 ⑥ 结果回填 Session ⑦ 回到 ②。达到 {@code
 * maxIterations}（默认 10）强制结束并返回"达到最大轮数，已停止"（坑一兜底）。
 *
 * <p>循环只做调度（FR-013）：拼 prompt、调模型、执行工具全部交给各自职责组件；每轮响应先累积进 Session 再继续（坑 三：事后可审计、下一轮接得上）。{@link
 * ChatResponse} 到 core {@link Message} 的转换是本类私有职责（唯一调用方）， Session 历史保持框架无关、可 JSON 序列化。
 */
public final class ReActLoop {

  private static final Logger LOG = LoggerFactory.getLogger(ReActLoop.class);

  static final String MAX_ITERATIONS_MESSAGE = "达到最大轮数，已停止";

  private final LlmGateway llmGateway;
  private final PromptBuilder promptBuilder;
  private final ToolExecutor toolExecutor;

  public ReActLoop(LlmGateway llmGateway, PromptBuilder promptBuilder, ToolExecutor toolExecutor) {
    this.llmGateway = llmGateway;
    this.promptBuilder = promptBuilder;
    this.toolExecutor = toolExecutor;
  }

  /** 跑一轮完整循环，返回 Agent 最终答复（或达最大轮数时的停止文案）。 */
  public String run(Session session, String userMessage, Profile profile) {
    session.append(Message.user(userMessage));
    int maxIterations = profile.settings().maxIterations();
    for (int iteration = 1; iteration <= maxIterations; iteration++) {
      Prompt prompt = promptBuilder.build(session, profile);
      long startedAt = System.currentTimeMillis();
      ChatResponse response = llmGateway.chat(session.id(), profile, prompt);
      long durationMs = System.currentTimeMillis() - startedAt;
      session.append(toAssistantMessage(response));
      // session.id() 为用户可控值，不入日志参数（防 CRLF 注入，001 先例）；关联审计在 llm_calls 表内
      LOG.info(
          "第 {} 轮 LLM 调用完成: durationMs={} 含工具请求={}", iteration, durationMs, hasToolCalls(response));
      if (!hasToolCalls(response)) {
        return textOf(response);
      }
      for (AssistantMessage.ToolCall call : toolCallsOf(response)) {
        ToolResult result = toolExecutor.execute(session.id(), call);
        session.append(toToolMessage(call, result));
      }
    }
    LOG.warn("达到最大轮数强制停止: maxIterations={}", maxIterations);
    return MAX_ITERATIONS_MESSAGE;
  }

  /** ChatResponse → core Message（assistant 含工具调用请求；框架无关的 Session 历史形态）。 */
  private Message toAssistantMessage(ChatResponse response) {
    AssistantMessage output = response.getResult().getOutput();
    // getToolCalls() 契约非空（无工具调用时返回空列表）
    List<Message.ToolCallRequest> toolCalls =
        output.getToolCalls().stream()
            .map(c -> new Message.ToolCallRequest(c.id(), c.name(), c.arguments()))
            .toList();
    String text = output.getText() == null ? "" : output.getText();
    return new Message(Message.MessageRole.ASSISTANT, text, toolCalls, List.of());
  }

  /** 工具结果 → TOOL 消息：失败时带 retryable 提示回传（重试是 LLM 下一轮的决定）。 */
  private Message toToolMessage(AssistantMessage.ToolCall call, ToolResult result) {
    String content;
    if (result.success()) {
      content = result.content();
    } else {
      content = result.errorMessage() + (result.retryable() ? "（可重试）" : "（不建议重试）");
    }
    Message.ToolCallResult toolResult = new Message.ToolCallResult(call.id(), content);
    return new Message(Message.MessageRole.TOOL, content, List.of(), List.of(toolResult));
  }

  private boolean hasToolCalls(ChatResponse response) {
    return response.getResult().getOutput().hasToolCalls();
  }

  private List<AssistantMessage.ToolCall> toolCallsOf(ChatResponse response) {
    return response.getResult().getOutput().getToolCalls();
  }

  private String textOf(ChatResponse response) {
    return response.getResult().getOutput().getText();
  }
}
