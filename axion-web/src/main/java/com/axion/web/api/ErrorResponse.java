package com.oryxos.web.api;

import java.time.Instant;

/**
 * 全局异常的标准 JSON 错误体（见 skill 步骤 6：errorCode / message / timestamp）。
 *
 * @param errorCode 错误码，与 HTTP 状态码一致
 * @param message 人类可读错误消息
 * @param timestamp 错误发生时间（ISO-8601）
 */
public record ErrorResponse(int errorCode, String message, Instant timestamp) {

  public static ErrorResponse of(ErrorCode errorCode, String message) {
    return new ErrorResponse(errorCode.httpStatus(), message, Instant.now());
  }
}
