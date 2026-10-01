package com.oryxos.web.api;

import java.time.Instant;

/**
 * 统一响应信封（OryxOS Web Service API 约定，见 CLAUDE.md「Web Service API」）。
 *
 * <p>code 约定：{@code 0} 表示成功；非 0 为错误码，与 HTTP 状态码一致 （400 / 404 / 500 / 503），见 {@link ErrorCode}。
 *
 * @param <T> 业务数据类型
 * @param code 响应码，0 = 成功
 * @param message 人类可读消息
 * @param data 业务数据，错误时为 {@code null}
 * @param timestamp 响应时间（ISO-8601）
 */
public record ApiResponse<T>(int code, String message, T data, Instant timestamp) {

  /** 成功响应。 */
  public static <T> ApiResponse<T> ok(T data) {
    return new ApiResponse<>(0, "success", data, Instant.now());
  }

  /** 失败响应，code 取自 {@link ErrorCode}。 */
  public static <T> ApiResponse<T> error(ErrorCode errorCode, String message) {
    return new ApiResponse<>(errorCode.httpStatus(), message, null, Instant.now());
  }
}
