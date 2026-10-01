package com.oryxos.storage;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.scheduling.support.CronExpression;

/**
 * {@link ScheduledTaskStore} 的 JPA 实现（010-scheduler-mgmt，契约与实现同落 storage——⑧ 落位拍板）。
 *
 * <p>关键语义：
 *
 * <ul>
 *   <li>重注册（重启/重载）只覆盖定义列与 next_run_at，enabled/last_*、run_count 状态列保留——「重启不失忆」（US5）
 *   <li>{@link #recordExecution} = 写历史 + 状态迁移（last_run/last_status/run_count/next_run），两次写非原子、
 *       单实例最终一致（spec 接受）
 *   <li>next_run_at 计算用 {@link CronExpression#parse(String)}（H3 已核实公开 API，T001）： {@code
 *       parse(cron).next(ZonedDateTime.ofInstant(baseline, zone)).toInstant()}，zone 空按系统时区（008 口径）
 * </ul>
 */
public class JpaScheduledTaskStore implements ScheduledTaskStore {

  private static final String STATUS_SUCCESS = "success";
  private static final String STATUS_FAILURE = "failure";

  private final ScheduledTaskRepository taskRepository;
  private final TaskExecutionRepository executionRepository;

  public JpaScheduledTaskStore(
      ScheduledTaskRepository taskRepository, TaskExecutionRepository executionRepository) {
    this.taskRepository = taskRepository;
    this.executionRepository = executionRepository;
  }

  @Override
  public void register(ScheduledTaskView task, Instant nextRunAt) {
    Optional<ScheduledTask> existing = taskRepository.findById(task.taskId());
    ScheduledTask entity;
    if (existing.isPresent()) {
      entity = existing.get();
    } else {
      entity =
          new ScheduledTask(
              task.taskId(), task.profileName(), task.cron(), task.zone(), task.message());
    }
    // 重注册同样走 mergeDefinition：定义列与 next_run_at 以本次文件内容为准，状态列（enabled/last_*、run_count）不动
    entity.mergeDefinition(task.profileName(), task.cron(), task.zone(), task.message(), nextRunAt);
    taskRepository.save(entity);
  }

  @Override
  public void recordExecution(TaskExecutionView execution) {
    ScheduledTask task =
        taskRepository
            .findById(execution.taskId())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "定时任务未登记却产生执行记录: " + execution.taskId() + "（登记先于执行的顺序被破坏）"));
    task.applyExecutionResult(
        execution.startedAt(),
        execution.success() ? STATUS_SUCCESS : STATUS_FAILURE,
        nextRunAfter(task.getCron(), task.getZone(), finishedAt(execution)));
    taskRepository.save(task);
    executionRepository.save(toEntity(execution));
  }

  @Override
  public boolean isEnabled(String taskId) {
    return taskRepository.findById(taskId).map(ScheduledTask::getEnabled).orElse(false);
  }

  @Override
  public void setEnabled(String taskId, boolean enabled) {
    ScheduledTask task =
        taskRepository
            .findById(taskId)
            .orElseThrow(() -> new IllegalStateException("定时任务不存在: " + taskId));
    task.applyEnabled(enabled); // 只动 enabled，next_run_at 保留原值（Clarifications 2026-09-09）
    taskRepository.save(task);
  }

  @Override
  public List<ScheduledTaskView> list() {
    return taskRepository.findAll().stream()
        .sorted(Comparator.comparing(ScheduledTask::getTaskId)) // F3：taskId 升序，稳定可测
        .map(this::toView)
        .toList();
  }

  @Override
  public List<TaskExecutionView> executions(String taskId) {
    return executionRepository.findByTaskIdOrderByIdDesc(taskId).stream()
        .map(this::toView)
        .toList();
  }

  /** 执行完成时刻 = startedAt + durationMs；durationMs 缺失（异常路径）按 startedAt。 */
  private Instant finishedAt(TaskExecutionView execution) {
    return execution.durationMs() == null
        ? execution.startedAt()
        : execution.startedAt().plusMillis(execution.durationMs());
  }

  /** H3 已核实（T001）：CronExpression.parse 公开可用，zone 参与方式与 CronTrigger 内部一致。 */
  private Instant nextRunAfter(String cron, String zone, Instant baseline) {
    ZoneId zoneId = zone == null || zone.isBlank() ? ZoneId.systemDefault() : ZoneId.of(zone);
    CronExpression expression = CronExpression.parse(cron);
    if (expression
        == null) { // parse 契约 @Nullable（SpotBugs NP 门禁）——非法 cron 不可能走到这（注册期 CronTrigger 已拦），防御不静默
      throw new IllegalStateException("cron 表达式非法: " + cron);
    }
    ZonedDateTime next = expression.next(ZonedDateTime.ofInstant(baseline, zoneId));
    if (next == null) { // next 契约同为 @Nullable——六段 cron 恒有下一次，防御不静默
      throw new IllegalStateException("cron 无下一次触发时刻: " + cron);
    }
    return next.toInstant();
  }

  private ScheduledTaskView toView(ScheduledTask entity) {
    return new ScheduledTaskView(
        entity.getTaskId(),
        entity.getProfileName(),
        entity.getCron(),
        entity.getZone(),
        entity.getMessage(),
        entity.getEnabled(),
        entity.getNextRunAt(),
        entity.getLastRunAt(),
        entity.getLastStatus(),
        entity.getRunCount());
  }

  private TaskExecutionView toView(TaskExecution entity) {
    return new TaskExecutionView(
        entity.getId(),
        entity.getTaskId(),
        entity.getSessionId(),
        entity.getStartedAt(),
        entity.getSuccess(),
        entity.getErrorMessage(),
        entity.getDurationMs());
  }

  private TaskExecution toEntity(TaskExecutionView view) {
    return new TaskExecution(
        view.taskId(),
        view.sessionId(),
        view.startedAt(),
        view.success(),
        view.errorMessage(),
        view.durationMs());
  }
}
