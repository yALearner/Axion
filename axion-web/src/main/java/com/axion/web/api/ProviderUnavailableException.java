package com.oryxos.web.api;

/** Provider 依赖不可用（503）——模型服务故障时抛出，由 {@link GlobalExceptionHandler} 统一转换为错误信封。 */
public class ProviderUnavailableException extends RuntimeException {

  public ProviderUnavailableException(String message) {
    super(message);
  }
}
