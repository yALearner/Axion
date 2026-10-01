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
 * 定时任务执行历史行（task_executions 表，010-scheduler-mgmt）。
 *
 * <p>成功失败都记（宪法 V 同源）：失败时 {@link #success} 为 false 且 {@link #errorMessage} 存人可读消息（非堆栈——
 * NFR-003）。表结构由 {@code schema.sql} 手工维护，不依赖 hibernate.ddl-auto 自动迁移。
 */
@Entity
@Table(name = "task_executions")
public class TaskExecution {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "task_id", nullable = false)
  private String taskId;

  @Column(name = "session_id")
  private String sessionId;

  /** ISO-8601 TEXT 存储 UTC（SQLite 无原生 TIMESTAMP）。 */
  @Column(name = "started_at", nullable = false)
  @Convert(converter = InstantTextConverter.class)
  private Instant startedAt;

  @Column(nullable = false)
  private Boolean success;

  @Column(name = "error_message")
  private String errorMessage;

  @Column(name = "duration_ms")
  private Long durationMs;

  protected TaskExecution() {
    // JPA 需要
  }

  public TaskExecution(
      String taskId,
      String sessionId,
      Instant startedAt,
      Boolean success,
      String errorMessage,
      Long durationMs) {
    this.taskId = taskId;
    this.sessionId = sessionId;
    this.startedAt = startedAt;
    this.success = success;
    this.errorMessage = errorMessage;
    this.durationMs = durationMs;
  }

  public Long getId() {
    return id;
  }

  public String getTaskId() {
    return taskId;
  }

  public String getSessionId() {
    return sessionId;
  }

  public Instant getStartedAt() {
    return startedAt;
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
}
