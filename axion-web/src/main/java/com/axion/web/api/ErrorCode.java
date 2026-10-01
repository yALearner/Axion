package com.oryxos.web.api;

/** 全局错误码。code 与 HTTP 状态码一致，保证客户端可依据状态码直接处理。 */
public enum ErrorCode {

  /** 请求参数非法。 */
  BAD_REQUEST(400),
  /** 资源不存在。 */
  NOT_FOUND(404),
  /** 服务器内部错误。 */
  INTERNAL_ERROR(500),
  /** 服务不可用。 */
  SERVICE_UNAVAILABLE(503),
  /** Agent 调用超时（60 秒上限，009-web-service 补值）。 */
  GATEWAY_TIMEOUT(504);

  private final int httpStatus;

  ErrorCode(int httpStatus) {
    this.httpStatus = httpStatus;
  }

  public int httpStatus() {
    return httpStatus;
  }
}
