package com.oryxos.web.api;

/** 通用资源不存在（404）——Agent 名等资源查不到时抛出，由 {@link GlobalExceptionHandler} 统一转换为错误信封。 */
public class ResourceNotFoundException extends RuntimeException {

  public ResourceNotFoundException(String message) {
    super(message);
  }
}
