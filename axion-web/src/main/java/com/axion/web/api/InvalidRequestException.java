package com.oryxos.web.api;

/** 请求参数非法（400）——消息为空/超 32KB 等防呆校验失败时抛出，由 {@link GlobalExceptionHandler} 统一转换为错误信封。 */
public class InvalidRequestException extends RuntimeException {

  public InvalidRequestException(String message) {
    super(message);
  }
}
