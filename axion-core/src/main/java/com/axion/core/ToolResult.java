package com.oryxos.core;

/** Tool 执行结果（{@link OryxTool#execute} 的返回类型）。 */
public record ToolResult(boolean success, String content, String errorMessage, boolean retryable) {

  public static ToolResult success(String content) {
    return new ToolResult(true, content, null, false);
  }

  public static ToolResult failure(String errorMessage, boolean retryable) {
    return new ToolResult(false, null, errorMessage, retryable);
  }
}
