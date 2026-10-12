package com.axion.web.api;

/**
 * 012-agent-mgmt 管理域业务异常——携带 {@link ErrorCode}（400/404/503 各自区分，2.5 口径），由 GlobalExceptionHandler
 * 统一转错误信封。
 */
public class AgentManagementException extends RuntimeException {

  private final ErrorCode code;

  public AgentManagementException(ErrorCode code, String message) {
    super(message);
    this.code = code;
  }

  public ErrorCode code() {
    return code;
  }

  public static AgentManagementException exists(String name) {
    return new AgentManagementException(ErrorCode.AGENT_EXISTS, "Agent 已存在: " + name);
  }

  public static AgentManagementException notFound(String name) {
    return new AgentManagementException(ErrorCode.AGENT_NOT_FOUND, "Agent 不存在: " + name);
  }

  public static AgentManagementException invalid(String message) {
    return new AgentManagementException(ErrorCode.AGENT_INVALID, message);
  }

  public static AgentManagementException generateInvalid(String message) {
    return new AgentManagementException(ErrorCode.GENERATE_INVALID, message);
  }

  public static AgentManagementException authorModelMissing() {
    return new AgentManagementException(
        ErrorCode.AUTHOR_MODEL_MISSING, "生成模型未配置: axion.author.model（配置后重试）");
  }
}
