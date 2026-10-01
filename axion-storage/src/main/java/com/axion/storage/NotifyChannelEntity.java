package com.oryxos.storage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 通知渠道注册行（notify_channels 表，004-notify 交付）。
 *
 * <p>口径照技术方案 §6.8：{@link #name} 为注册名（Agent 正文按此名引用渠道）；{@link #type} 核心阶段均为 webhook；{@link #url}
 * 不进对话、不进日志、不进 frontmatter；{@link #description} 可选。渠道 CRUD 归 Web Service 节，本节只交表 + 仓储 + 解析服务。
 *
 * <p>表结构由 {@code schema.sql} 手工维护，不依赖 hibernate.ddl-auto 自动迁移（坑八口径）； 无时间列故不涉 {@link
 * InstantTextConverter}。
 */
@Entity
@Table(name = "notify_channels")
public class NotifyChannelEntity {

  @Id
  @Column(nullable = false)
  private String name;

  @Column(nullable = false)
  private String type;

  @Column(nullable = false)
  private String url;

  private String description;

  protected NotifyChannelEntity() {
    // JPA 需要
  }

  public NotifyChannelEntity(String name, String type, String url, String description) {
    this.name = name;
    this.type = type;
    this.url = url;
    this.description = description;
  }

  public String getName() {
    return name;
  }

  public String getType() {
    return type;
  }

  public String getUrl() {
    return url;
  }

  public String getDescription() {
    return description;
  }
}
