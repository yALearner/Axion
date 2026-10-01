package com.oryxos.storage;

import java.time.Instant;
import java.util.List;

/**
 * 定时任务状态与历史存储契约（010-scheduler-mgmt）。
 *
 * <p>落位拍板（2026-09-09，停止清单 3 触发后用户拍板 A）：接口与实现同落 storage——Maven 依赖方向 storage 不得依赖 core（core→storage
 * 既有，反向会循环引用），与 SessionRepository 同构：core 的 AgentScheduler 直接消费本接口， JPA 实体细节封装在 {@link
 * JpaScheduledTaskStore} 内不上浮。
 *
 * <p>定义源仍是 AGENT.md frontmatter 的 schedules，本契约只存「状态 + 历史」不作为定义源——重启时 AgentScheduler 从文件重新注册。方法语义：
 *
 * <ul>
 *   <li>{@link #register}：首次登记落行（enabled=true、run_count=0）；重注册（重启/重载）只覆盖定义列与
 *       next_run_at，enabled/last_*、run_count 状态列保留——「重启不失忆」的前提（US5）
 *   <li>{@link #recordExecution}：写一条执行历史（成功失败都记，宪法 V 同源），同时把任务状态迁移到
 *       last_run/last_status/run_count/next_run（执行完成时刻为基准重算下次触发）
 *   <li>{@link #setEnabled}：只动 enabled，next_run_at 保留原值（Clarifications 2026-09-09）
 * </ul>
 */
public interface ScheduledTaskStore {

  /** 登记/重注册一条定时任务（含算出的 nextRunAt）。 */
  void register(ScheduledTaskView task, Instant nextRunAt);

  /** 记一次真正执行的结果（成败都记）并更新任务状态（last_run/last_status/run_count/next_run）。 */
  void recordExecution(TaskExecutionView execution);

  /** 任务当前是否启用（停用 = 到点跳过、不执行、不记历史）。 */
  boolean isEnabled(String taskId);

  /** 启用/停用（管理台开关）。 */
  void setEnabled(String taskId, boolean enabled);

  /** 全部任务状态（按 taskId 升序，稳定可测——S5 analyze F3）。 */
  List<ScheduledTaskView> list();

  /** 某任务的执行历史（最新在前）。 */
  List<TaskExecutionView> executions(String taskId);
}
