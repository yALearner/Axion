package com.oryxos.web.api;

/** 会话不存在（404）——按 session id 查不到会话时抛出，由 {@link GlobalExceptionHandler} 统一转换为错误信封。 */
public class SessionNotFoundException extends RuntimeException {

  public SessionNotFoundException(String sessionId) {
    super("Session 不存在: " + sessionId);
  }
}
