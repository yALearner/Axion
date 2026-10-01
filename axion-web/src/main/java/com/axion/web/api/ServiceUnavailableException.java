package com.oryxos.web.api;

/** 服务不可用异常（503）。业务代码在依赖能力不可用时抛出，由 {@link GlobalExceptionHandler} 统一转换为标准错误体。 */
public class ServiceUnavailableException extends RuntimeException {

  public ServiceUnavailableException(String message) {
    super(message);
  }

  public ServiceUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
