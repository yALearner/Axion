package com.oryxos.storage;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 会话存档行（sessions 表，003-cli 交付）。
 *
 * <p>字段照技术方案 §9.2：会话标识由 SessionManager 按 channel|user|profile 唯一拼接（H4 不变量四）； {@link #messagesJson}
 * 对话历史整体 JSON 序列化一列存（核心阶段不按条拆表——课件第 18 节）； 时间戳 ISO-8601 TEXT（复用 {@link
 * InstantTextConverter}）；归档流转（active→archived）归第 26 节， 本课只写 active。
 *
 * <p>表结构由 {@code schema.sql} 手工维护，不依赖 hibernate.ddl-auto 自动迁移（坑八口径）。
 */
@Entity
@Table(name = "sessions")
public class SessionEntity {

  @Id
  @Column(name = "session_id", nullable = false)
  private String sessionId;

  @Column(name = "profile_name", nullable = false)
  private String profileName;

  @Column(nullable = false)
  private String channel;

  @Column(name = "user_id", nullable = false)
  private String userId;

  @Column(name = "messages_json")
  private String messagesJson;

  @Column(nullable = false)
  private String status;

  @Column(name = "created_at", nullable = false)
  @Convert(converter = InstantTextConverter.class)
  private Instant createdAt;

  @Column(name = "last_active_at", nullable = false)
  @Convert(converter = InstantTextConverter.class)
  private Instant lastActiveAt;

  @Column(name = "archived_at")
  @Convert(converter = InstantTextConverter.class)
  private Instant archivedAt;

  protected SessionEntity() {
    // JPA 需要
  }

  public SessionEntity(
      String sessionId,
      String profileName,
      String channel,
      String userId,
      String messagesJson,
      String status,
      Instant createdAt,
      Instant lastActiveAt,
      Instant archivedAt) {
    this.sessionId = sessionId;
    this.profileName = profileName;
    this.channel = channel;
    this.userId = userId;
    this.messagesJson = messagesJson;
    this.status = status;
    this.createdAt = createdAt;
    this.lastActiveAt = lastActiveAt;
    this.archivedAt = archivedAt;
  }

  /** 保存时更新历史与最后活跃时间；created_at/status/archived_at 保留既有值。 */
  public void updateHistory(String messagesJson, Instant lastActiveAt) {
    this.messagesJson = messagesJson;
    this.lastActiveAt = lastActiveAt;
  }

  /** 归档（009-web-service DELETE /sessions/{id} 落点）：状态流转为 archived + 记录归档时间。 */
  public void archive(Instant archivedAt) {
    this.status = "archived";
    this.archivedAt = archivedAt;
  }

  public String getSessionId() {
    return sessionId;
  }

  public String getProfileName() {
    return profileName;
  }

  public String getChannel() {
    return channel;
  }

  public String getUserId() {
    return userId;
  }

  public String getMessagesJson() {
    return messagesJson;
  }

  public String getStatus() {
    return status;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getLastActiveAt() {
    return lastActiveAt;
  }

  public Instant getArchivedAt() {
    return archivedAt;
  }
}
