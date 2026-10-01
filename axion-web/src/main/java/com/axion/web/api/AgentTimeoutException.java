package com.oryxos.web.api;

/** Agent 调用超时（504）——60 秒上限触发时抛出，由 {@link GlobalExceptionHandler} 统一转换为错误信封。 */
public class AgentTimeoutException extends RuntimeException {

  public AgentTimeoutException(String message) {
    super(message);
  }
}
