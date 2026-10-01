package com.oryxos.storage;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 工具调用审计记录（tool_invocations 表，day one 写入）。
 *
 * <p>成功与失败都必须落库：失败时 {@link #success} 为 false 且 {@link #errorMessage} 有值——与 001 的 llm_calls 同口径（宪法
 * V）。表结构由 {@code schema.sql} 手工维护，不依赖 hibernate.ddl-auto 自动迁移 （SQLite 的 ALTER TABLE 支持很弱）。
 */
@Entity
@Table(name = "tool_invocations")
public class ToolInvocation {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "session_id", nullable = false)
  private String sessionId;

  @Column(name = "tool_name", nullable = false)
  private String toolName;

  @Column(name = "input_json")
  private String inputJson;

  @Column(name = "result_json")
  private String resultJson;

  @Column(nullable = false)
  private Boolean success;

  @Column(name = "error_message")
  private String errorMessage;

  @Column(name = "duration_ms")
  private Long durationMs;

  /** ISO-8601 TEXT 存储（SQLite 无原生 TIMESTAMP）。 */
  @Column(name = "created_at", nullable = false)
  @Convert(converter = InstantTextConverter.class)
  private Instant createdAt;

  protected ToolInvocation() {
    // JPA 需要
  }

  public ToolInvocation(
      String sessionId,
      String toolName,
      String inputJson,
      String resultJson,
      Boolean success,
      String errorMessage,
      Long durationMs,
      Instant createdAt) {
    this.sessionId = sessionId;
    this.toolName = toolName;
    this.inputJson = inputJson;
    this.resultJson = resultJson;
    this.success = success;
    this.errorMessage = errorMessage;
    this.durationMs = durationMs;
    this.createdAt = createdAt;
  }

  public Long getId() {
    return id;
  }

  public String getSessionId() {
    return sessionId;
  }

  public String getToolName() {
    return toolName;
  }

  public String getInputJson() {
    return inputJson;
  }

  public String getResultJson() {
    return resultJson;
  }

  public Boolean getSuccess() {
    return success;
  }

  public String getErrorMessage() {
    return errorMessage;
  }

  public Long getDurationMs() {
    return durationMs;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
