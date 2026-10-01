package com.oryxos.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * JpaScheduledTaskStore 验收 harness——六方法行为 + 关键语义：重注册保留状态列（重启不失忆）、recordExecution 成败都
 * 落账并迁移状态（last_run/last_status/run_count/next_run 重算）、setEnabled 只动 enabled（next_run_at 保留）。
 */
class JpaScheduledTaskStoreTest {

  private static final Instant BASE = Instant.parse("2026-09-09T00:00:00Z");
  private static final String CRON = "0 0 8 * * *";
  private static final String ZONE = "Asia/Shanghai";

  private final ScheduledTaskRepository taskRepository = mock(ScheduledTaskRepository.class);
  private final TaskExecutionRepository executionRepository = mock(TaskExecutionRepository.class);
  private final JpaScheduledTaskStore store =
      new JpaScheduledTaskStore(taskRepository, executionRepository);

  private ScheduledTaskView taskView() {
    return new ScheduledTaskView(
        "weather-8am", "weather-agent", CRON, ZONE, "生成今日天气和穿搭建议", true, BASE, null, null, 0);
  }

  private TaskExecutionView executionView(boolean success) {
    return new TaskExecutionView(
        null,
        "weather-8am",
        "scheduler|scheduler|weather-agent",
        BASE,
        success,
        success ? null : "模型调用失败",
        8000L);
  }

  // ---- register ----

  @Test
  @DisplayName("首次登记：落行 enabled=true、run_count=0、next_run_at=传入值")
  void registerInsertsFreshRow() {
    when(taskRepository.findById("weather-8am")).thenReturn(Optional.empty());

    store.register(taskView(), BASE);

    ArgumentCaptor<ScheduledTask> captor = ArgumentCaptor.forClass(ScheduledTask.class);
    verify(taskRepository).save(captor.capture());
    assertThat(captor.getValue().getEnabled()).isTrue();
    assertThat(captor.getValue().getRunCount()).isZero();
    assertThat(captor.getValue().getNextRunAt()).isEqualTo(BASE);
    assertThat(captor.getValue().getLastStatus()).isNull();
  }

  @Test
  @DisplayName("重注册保留状态列：enabled=false/run_count=5 不被覆盖，定义列与 next_run_at 刷新（重启不失忆）")
  void registerPreservesRuntimeState() {
    ScheduledTask existing = new ScheduledTask("weather-8am", "weather-agent", CRON, ZONE, "旧消息");
    existing.applyEnabled(false);
    existing.applyExecutionResult(BASE.minusSeconds(60), "success", BASE);
    when(taskRepository.findById("weather-8am")).thenReturn(Optional.of(existing));

    store.register(taskView(), BASE.plusSeconds(3600));

    ArgumentCaptor<ScheduledTask> captor = ArgumentCaptor.forClass(ScheduledTask.class);
    verify(taskRepository).save(captor.capture());
    assertThat(captor.getValue().getEnabled()).isFalse(); // 状态列不动
    assertThat(captor.getValue().getRunCount()).isEqualTo(1); // 状态列不动
    assertThat(captor.getValue().getLastStatus()).isEqualTo("success"); // 状态列不动
    assertThat(captor.getValue().getMessage()).isEqualTo("生成今日天气和穿搭建议"); // 定义列刷新
    assertThat(captor.getValue().getNextRunAt()).isEqualTo(BASE.plusSeconds(3600)); // next_run 刷新
  }

  // ---- recordExecution ----

  @Test
  @DisplayName("执行成功：历史一条 success=true + 任务状态迁移 success/run_count+1/next_run 重算")
  void recordExecutionSuccessMigratesState() {
    ScheduledTask existing =
        new ScheduledTask("weather-8am", "weather-agent", CRON, ZONE, "生成今日天气和穿搭建议");
    when(taskRepository.findById("weather-8am")).thenReturn(Optional.of(existing));

    store.recordExecution(executionView(true));

    ArgumentCaptor<ScheduledTask> taskCaptor = ArgumentCaptor.forClass(ScheduledTask.class);
    verify(taskRepository).save(taskCaptor.capture());
    assertThat(taskCaptor.getValue().getLastRunAt()).isEqualTo(BASE);
    assertThat(taskCaptor.getValue().getLastStatus()).isEqualTo("success");
    assertThat(taskCaptor.getValue().getRunCount()).isEqualTo(1);
    // 执行完成时刻 = BASE + 8s（上海 08:00:08）→ 严格晚于当日 8 点的下一次匹配 = 次日 8 点（上海）= 2026-09-10T00:00:00Z
    assertThat(taskCaptor.getValue().getNextRunAt())
        .isEqualTo(
            ZonedDateTime.of(2026, 9, 10, 8, 0, 0, 0, ZoneId.of("Asia/Shanghai")).toInstant());

    ArgumentCaptor<TaskExecution> execCaptor = ArgumentCaptor.forClass(TaskExecution.class);
    verify(executionRepository).save(execCaptor.capture());
    assertThat(execCaptor.getValue().getSuccess()).isTrue();
    assertThat(execCaptor.getValue().getErrorMessage()).isNull();
    assertThat(execCaptor.getValue().getDurationMs()).isEqualTo(8000L);
  }

  @Test
  @DisplayName("执行失败：历史一条 success=false + error_message 人可读 + last_status=failure（成败都记，宪法 V）")
  void recordExecutionFailureMigratesState() {
    ScheduledTask existing =
        new ScheduledTask("weather-8am", "weather-agent", CRON, ZONE, "生成今日天气和穿搭建议");
    when(taskRepository.findById("weather-8am")).thenReturn(Optional.of(existing));

    store.recordExecution(executionView(false));

    ArgumentCaptor<ScheduledTask> taskCaptor = ArgumentCaptor.forClass(ScheduledTask.class);
    verify(taskRepository).save(taskCaptor.capture());
    assertThat(taskCaptor.getValue().getLastStatus()).isEqualTo("failure");
    assertThat(taskCaptor.getValue().getRunCount()).isEqualTo(1);

    ArgumentCaptor<TaskExecution> execCaptor = ArgumentCaptor.forClass(TaskExecution.class);
    verify(executionRepository).save(execCaptor.capture());
    assertThat(execCaptor.getValue().getSuccess()).isFalse();
    assertThat(execCaptor.getValue().getErrorMessage()).isEqualTo("模型调用失败");
  }

  @Test
  @DisplayName("未登记任务产生执行记录：明确报错不静默（登记先于执行的顺序被破坏）")
  void recordExecutionForUnknownTaskFailsLoudly() {
    when(taskRepository.findById("ghost")).thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                store.recordExecution(
                    new TaskExecutionView(null, "ghost", null, BASE, true, null, 100L)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("ghost");
    verify(executionRepository, never()).save(any());
  }

  // ---- isEnabled / setEnabled ----

  @Test
  @DisplayName("isEnabled：未知任务 false（安全跳过）；setEnabled 只动 enabled、next_run_at 保留")
  void enabledSemantics() {
    when(taskRepository.findById("ghost")).thenReturn(Optional.empty());
    assertThat(store.isEnabled("ghost")).isFalse();

    ScheduledTask existing =
        new ScheduledTask("weather-8am", "weather-agent", CRON, ZONE, "生成今日天气和穿搭建议");
    existing.mergeDefinition("weather-agent", CRON, ZONE, "生成今日天气和穿搭建议", BASE);
    when(taskRepository.findById("weather-8am")).thenReturn(Optional.of(existing));

    store.setEnabled("weather-8am", false);

    ArgumentCaptor<ScheduledTask> captor = ArgumentCaptor.forClass(ScheduledTask.class);
    verify(taskRepository).save(captor.capture());
    assertThat(captor.getValue().getEnabled()).isFalse();
    assertThat(captor.getValue().getNextRunAt()).isEqualTo(BASE); // 保留原值（Clarifications 2026-09-09）
    assertThat(captor.getValue().getRunCount()).isZero();
  }

  @Test
  @DisplayName("setEnabled 未知任务：明确报错不静默")
  void setEnabledForUnknownTaskFailsLoudly() {
    when(taskRepository.findById("ghost")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> store.setEnabled("ghost", false))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("ghost");
  }

  // ---- list / executions ----

  @Test
  @DisplayName("list 按 taskId 升序（F3）；executions 最新在前")
  void listSortedByTaskId() {
    ScheduledTask a = new ScheduledTask("a-task", "a", CRON, ZONE, "m");
    ScheduledTask b = new ScheduledTask("b-task", "b", CRON, ZONE, "m");
    when(taskRepository.findAll()).thenReturn(List.of(b, a));

    assertThat(store.list())
        .extracting(ScheduledTaskView::taskId)
        .containsExactly("a-task", "b-task");

    TaskExecution older =
        new TaskExecution("a-task", null, BASE.minusSeconds(60), true, null, 100L);
    TaskExecution newer = new TaskExecution("a-task", null, BASE, true, null, 200L);
    when(executionRepository.findByTaskIdOrderByIdDesc("a-task")).thenReturn(List.of(newer, older));

    List<TaskExecutionView> executions = store.executions("a-task");
    assertThat(executions)
        .extracting(TaskExecutionView::startedAt)
        .containsExactly(BASE, BASE.minusSeconds(60));
  }
}
