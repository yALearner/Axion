package com.oryxos.storage;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 定时任务状态行（scheduled_tasks 表，010-scheduler-mgmt）。
 *
 * <p>定义源是 AGENT.md frontmatter 的 schedules（含 id），本表只存「状态 + 登记信息」不作为定义源——重启时从文件重新 注册（重注册只覆盖定义列与
 * next_run_at，enabled/last_*、run_count 等状态列保留）。表结构由 {@code schema.sql} 手工维护，不依赖 hibernate.ddl-auto
 * 自动迁移（SQLite 的 ALTER TABLE 支持很弱）。
 *
 * <p>无通用 setter（2026-09-05 拍板）：修改路径收口在领域状态迁移方法 {@link #mergeDefinition} / {@link
 * #applyExecutionResult} / {@link #applyEnabled}，全部经 {@link JpaScheduledTaskStore} 调用。
 */
@Entity
@Table(name = "scheduled_tasks")
public class ScheduledTask {

  @Id
  @Column(name = "task_id")
  private String taskId;

  @Column(name = "profile_name", nullable = false)
  private String profileName;

  @Column(nullable = false)
  private String cron;

  @Column private String zone;

  @Column(nullable = false)
  private String message;

  @Column(nullable = false)
  private Boolean enabled;

  /** ISO-8601 TEXT 存储 UTC（SQLite 无原生 TIMESTAMP）。 */
  @Column(name = "next_run_at")
  @Convert(converter = InstantTextConverter.class)
  private Instant nextRunAt;

  /** ISO-8601 TEXT 存储 UTC（SQLite 无原生 TIMESTAMP）。 */
  @Column(name = "last_run_at")
  @Convert(converter = InstantTextConverter.class)
  private Instant lastRunAt;

  @Column(name = "last_status")
  private String lastStatus;

  @Column(name = "run_count", nullable = false)
  private Integer runCount;

  protected ScheduledTask() {
    // JPA 需要
  }

  /** 首次登记构造：enabled 默认启用、run_count 从 0 起（状态列只在此处落初值）。 */
  public ScheduledTask(
      String taskId, String profileName, String cron, String zone, String message) {
    this.taskId = taskId;
    this.profileName = profileName;
    this.cron = cron;
    this.zone = zone;
    this.message = message;
    this.enabled = true;
    this.runCount = 0;
  }

  /** 重注册（重启/重载）：定义列与计划下次触发以本次文件内容为准，状态列（enabled/last_*、run_count）不动。 */
  public void mergeDefinition(
      String profileName, String cron, String zone, String message, Instant nextRunAt) {
    this.profileName = profileName;
    this.cron = cron;
    this.zone = zone;
    this.message = message;
    this.nextRunAt = nextRunAt;
  }

  /** 一次真正执行的结果：last_run/last_status/run_count 迁移，next_run 重算为本次执行完成后的下一次匹配时刻。 */
  public void applyExecutionResult(Instant runAt, String status, Instant nextRunAt) {
    this.lastRunAt = runAt;
    this.lastStatus = status;
    this.runCount = this.runCount + 1;
    this.nextRunAt = nextRunAt;
  }

  /** 管理台启用/停用：只动 enabled，next_run_at 保留原值（Clarifications 2026-09-09）。 */
  public void applyEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public String getTaskId() {
    return taskId;
  }

  public String getProfileName() {
    return profileName;
  }

  public String getCron() {
    return cron;
  }

  public String getZone() {
    return zone;
  }

  public String getMessage() {
    return message;
  }

  public Boolean getEnabled() {
    return enabled;
  }

  public Instant getNextRunAt() {
    return nextRunAt;
  }

  public Instant getLastRunAt() {
    return lastRunAt;
  }

  public String getLastStatus() {
    return lastStatus;
  }

  public Integer getRunCount() {
    return runCount;
  }
}
