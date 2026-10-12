package com.axion.web.api;

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
  GATEWAY_TIMEOUT(504),
  /** Agent 已存在（012-agent-mgmt 2.5 口径：name 冲突第一步就拒，坑十八）。 */
  AGENT_EXISTS(400),
  /** Agent 不存在（查/改/删/会话/记忆目标缺失）。 */
  AGENT_NOT_FOUND(404),
  /** Agent 定义非法（AGENT.md 字段缺失/解析失败/provider 不存在）。 */
  AGENT_INVALID(400),
  /** 生成产物非法（坑十四：LLM 产出的 AGENT.md 无法解析，带可读原因）。 */
  GENERATE_INVALID(400),
  /** 生成模型未配置（坑十五：axion.author.model 留空，不发 model=null）。 */
  AUTHOR_MODEL_MISSING(503);

  private final int httpStatus;

  ErrorCode(int httpStatus) {
    this.httpStatus = httpStatus;
  }

  public int httpStatus() {
    return httpStatus;
  }
}
