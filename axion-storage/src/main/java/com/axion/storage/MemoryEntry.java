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
 * 长期记忆条目（memory_entries 表，sqlite 档的行存储——FR-4）。
 *
 * <p>分区语义与 MEMORY.md 两区块一一对应：{@link #scope} 为 {@code CORE} / {@code ARCHIVAL}（坑十七：由 Agent 经
 * save_memory 显式声明）；{@code created_at} ISO-8601 TEXT 存储（SQLite 无原生 TIMESTAMP，复用 {@link
 * InstantTextConverter}）。表结构由 {@code schema.sql} 手工维护，不依赖 hibernate.ddl-auto 自动迁移（坑八）。
 *
 * <p>核心阶段只由 SqliteMemoryStore 写入；查询接口/管理端点归 Web Service 节直接消费本表口径。实体无 setter 收口修改路径（无 Lombok 拍板延续）。
 */
@Entity
@Table(name = "memory_entries")
public class MemoryEntry {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false)
  private String content;

  @Column(nullable = false)
  private String scope;

  /** ISO-8601 TEXT 存储（SQLite 无原生 TIMESTAMP）。 */
  @Column(name = "created_at", nullable = false)
  @Convert(converter = InstantTextConverter.class)
  private Instant createdAt;

  protected MemoryEntry() {
    // JPA 需要
  }

  public MemoryEntry(String content, String scope, Instant createdAt) {
    this.content = content;
    this.scope = scope;
    this.createdAt = createdAt;
  }

  public Long getId() {
    return id;
  }

  public String getContent() {
    return content;
  }

  public String getScope() {
    return scope;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
