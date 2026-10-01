package com.oryxos.core;

import java.util.List;

/**
 * 一次对话消息——供 Provider 调用与后续 ReAct 循环复用。
 *
 * <p>core 保持框架无关：工具调用请求/结果用轻量嵌套类型承载， 与 Spring AI 消息格式的转换在 oryxos-provider 适配层完成。
 */
public record Message(
    MessageRole role,
    String content,
    List<ToolCallRequest> toolCalls,
    List<ToolCallResult> toolResults) {

  /** 防御性拷贝：不暴露可变集合的内部表示。 */
  public Message {
    toolCalls = List.copyOf(toolCalls);
    toolResults = List.copyOf(toolResults);
  }

  @Override
  public List<ToolCallRequest> toolCalls() {
    return List.copyOf(toolCalls);
  }

  @Override
  public List<ToolCallResult> toolResults() {
    return List.copyOf(toolResults);
  }

  public enum MessageRole {
    SYSTEM,
    USER,
    ASSISTANT,
    TOOL
  }

  /** 模型请求的工具调用（只透传、不执行）。 */
  public record ToolCallRequest(String id, String name, String arguments) {}

  /** 工具执行结果（由 ToolExecutor 回填，后续节消费）。 */
  public record ToolCallResult(String toolCallId, String content) {}

  public static Message system(String content) {
    return new Message(MessageRole.SYSTEM, content, List.of(), List.of());
  }

  public static Message user(String content) {
    return new Message(MessageRole.USER, content, List.of(), List.of());
  }

  public static Message assistant(String content) {
    return new Message(MessageRole.ASSISTANT, content, List.of(), List.of());
  }
}
