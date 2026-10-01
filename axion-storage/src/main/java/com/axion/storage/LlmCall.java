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
 * LLM 调用审计记录（llm_calls 表，day one 写入）。
 *
 * <p>成功与失败都必须落库：失败时 {@link #success} 为 false 且 {@link #errorMessage} 有值。 表结构由 {@code schema.sql}
 * 手工维护，不依赖 hibernate.ddl-auto 自动迁移 （SQLite 的 ALTER TABLE 支持很弱）。
 */
@Entity
@Table(name = "llm_calls")
public class LlmCall {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "session_id", nullable = false)
  private String sessionId;

  @Column(nullable = false)
  private String provider;

  @Column(nullable = false)
  private String model;

  @Column(name = "prompt_tokens")
  private Integer promptTokens;

  @Column(name = "completion_tokens")
  private Integer completionTokens;

  @Column(name = "total_tokens")
  private Integer totalTokens;

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

  protected LlmCall() {
    // JPA 需要
  }

  public LlmCall(
      String sessionId,
      String provider,
      String model,
      Integer promptTokens,
      Integer completionTokens,
      Integer totalTokens,
      Boolean success,
      String errorMessage,
      Long durationMs,
      Instant createdAt) {
    this.sessionId = sessionId;
    this.provider = provider;
    this.model = model;
    this.promptTokens = promptTokens;
    this.completionTokens = completionTokens;
    this.totalTokens = totalTokens;
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

  public String getProvider() {
    return provider;
  }

  public String getModel() {
    return model;
  }

  public Integer getPromptTokens() {
    return promptTokens;
  }

  public Integer getCompletionTokens() {
    return completionTokens;
  }

  public Integer getTotalTokens() {
    return totalTokens;
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
