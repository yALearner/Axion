package com.oryxos.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.oryxos.storage.ScheduledTaskStore;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.ScheduledFuture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;

/**
 * AgentSchedulerRegisterTest（011 FR-4）——registerProfile 后句柄/登记就位、cron/zone 来自 Profile.schedules、id
 * 冲突文案（30 节注销前置）。
 */
class AgentSchedulerRegisterTest {

  private static Profile profile(
      String name, String taskId, String cron, String zone, String message) {
    return new Profile(
        name,
        null,
        new Profile.Identity("定时任务 Agent", "你是一个定时触发的运维助手"),
        new Profile.ProviderRef("deepseek", null, null),
        List.of(),
        List.of(),
        List.of(),
        List.of(new Profile.Schedule(taskId, cron, zone, message)),
        List.of(),
        new Profile.Settings(10, 20));
  }

  private static Profile twoSchedules() {
    return new Profile(
        "daily-reconcile",
        null,
        new Profile.Identity("对账小欧", null),
        new Profile.ProviderRef("deepseek", null, null),
        List.of(),
        List.of(),
        List.of(),
        List.of(
            new Profile.Schedule("morning", "0 0 9 * * *", "Asia/Shanghai", "早上好"),
            new Profile.Schedule("evening", "0 30 18 * * *", "Asia/Shanghai", "晚上好")),
        List.of(),
        new Profile.Settings(10, 20));
  }

  private ScheduledTaskStore store = mock(ScheduledTaskStore.class);

  private AgentScheduler scheduler(ThreadPoolTaskScheduler taskScheduler) {
    when(store.isEnabled(anyString())).thenReturn(true);
    return new AgentScheduler(
        taskScheduler,
        mock(ProfileRegistry.class),
        mock(SessionManager.class),
        mock(AgentService.class),
        store);
  }

  @Test
  @DisplayName("registerProfile 后 scheduledTasks 有句柄、scheduled_tasks 已登记（30 节注销/更新前置）")
  void registerProfilePutsHandleAndRegisters() {
    ThreadPoolTaskScheduler taskScheduler = mock(ThreadPoolTaskScheduler.class);
    when(taskScheduler.schedule(any(Runnable.class), any(CronTrigger.class)))
        .thenAnswer(inv -> mock(ScheduledFuture.class));
    AgentScheduler scheduler = scheduler(taskScheduler);
    Profile profile = twoSchedules();

    scheduler.registerProfile(profile);

    assertThat(scheduler.scheduledTaskCount()).isEqualTo(2); // ⑦c 句柄已登记
    ArgumentCaptor<com.oryxos.storage.ScheduledTaskView> captor =
        ArgumentCaptor.forClass(com.oryxos.storage.ScheduledTaskView.class);
    verify(store, org.mockito.Mockito.times(2)).register(captor.capture(), any());
    assertThat(captor.getAllValues().get(0).taskId()).isEqualTo("morning");
    assertThat(captor.getAllValues().get(0).profileName()).isEqualTo("daily-reconcile");
  }

  @Test
  @DisplayName("cron/zone 来自 Profile.schedules（CronTrigger 显式时区，008 口径）")
  void cronAndZoneComeFromProfileSchedules() {
    ThreadPoolTaskScheduler taskScheduler = mock(ThreadPoolTaskScheduler.class);
    when(taskScheduler.schedule(any(Runnable.class), any(CronTrigger.class)))
        .thenAnswer(inv -> mock(ScheduledFuture.class));
    AgentScheduler scheduler = scheduler(taskScheduler);

    scheduler.registerProfile(
        profile("ops-agent", "weather-8am", "0 0 8 * * *", "Asia/Shanghai", "早安"));

    ArgumentCaptor<CronTrigger> captor = ArgumentCaptor.forClass(CronTrigger.class);
    verify(taskScheduler).schedule(any(Runnable.class), captor.capture());
    assertThat(captor.getValue())
        .isEqualTo(new CronTrigger("0 0 8 * * *", TimeZone.getTimeZone("Asia/Shanghai")));
  }

  @Test
  @DisplayName("id 冲突报错指明冲突双方 Profile（010 文案不变）")
  void idConflictNamesBothProfiles() {
    ThreadPoolTaskScheduler taskScheduler = mock(ThreadPoolTaskScheduler.class);
    when(taskScheduler.schedule(any(Runnable.class), any(CronTrigger.class)))
        .thenAnswer(inv -> mock(ScheduledFuture.class));
    AgentScheduler scheduler = scheduler(taskScheduler);

    scheduler.registerProfile(profile("ops-agent", "same-id", "0 0 8 * * *", null, "早安"));

    assertThatThrownBy(
            () ->
                scheduler.registerProfile(
                    profile("other-agent", "same-id", "0 0 9 * * *", null, "日报")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("same-id")
        .hasMessageContaining("ops-agent")
        .hasMessageContaining("other-agent");
  }

  @Test
  @DisplayName("registerAll = 遍历 registry.list() 调 registerProfile（两条路径同一段代码）")
  void registerAllIteratesRegistryProfiles() {
    ThreadPoolTaskScheduler taskScheduler = mock(ThreadPoolTaskScheduler.class);
    when(taskScheduler.schedule(any(Runnable.class), any(CronTrigger.class)))
        .thenAnswer(inv -> mock(ScheduledFuture.class));
    ProfileRegistry registry = mock(ProfileRegistry.class);
    when(registry.list())
        .thenReturn(
            List.of(
                profile("a", "a-job", "0 0 8 * * *", null, "早上好"),
                profile("b", "b-job", "0 30 9 * * *", "Asia/Shanghai", "日报")));
    AgentScheduler scheduler =
        new AgentScheduler(
            taskScheduler, registry, mock(SessionManager.class), mock(AgentService.class), store);

    scheduler.registerAll();

    verify(taskScheduler, org.mockito.Mockito.times(2))
        .schedule(any(Runnable.class), any(CronTrigger.class));
    assertThat(scheduler.scheduledTaskCount()).isEqualTo(2);
  }

  @Test
  @DisplayName("无 schedules 的 Profile：registerProfile 零注册（合法空态）")
  void profileWithoutSchedulesRegistersNothing() {
    ThreadPoolTaskScheduler taskScheduler = mock(ThreadPoolTaskScheduler.class);
    AgentScheduler scheduler = scheduler(taskScheduler);
    Profile noSchedule =
        new Profile(
            "bare-agent",
            null,
            new Profile.Identity(null, null),
            new Profile.ProviderRef("deepseek", null, null),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            new Profile.Settings(10, 20));

    scheduler.registerProfile(noSchedule);

    assertThat(scheduler.scheduledTaskCount()).isZero();
    verify(store, org.mockito.Mockito.never()).register(any(), any());
  }
}
