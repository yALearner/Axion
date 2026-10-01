package com.oryxos.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.oryxos.storage.ScheduledTaskStore;
import com.oryxos.storage.ScheduledTaskView;
import com.oryxos.storage.TaskExecutionView;
import java.time.Instant;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.locks.Lock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;

/**
 * AgentScheduler 验收 harness（008-scheduler 奠基 + 010-scheduler-mgmt 增补）——别真等时间：runOnce/runNow
 * 直接调；cron 触发本身是 Spring 的事，只验"注册参数传对了"。008 四坑核心组 + 跳过/失败日志口径 + 注册组（⑦b/⑦c）；010 增补：登记进
 * ScheduledTaskStore（含 next_run_at）、id 冲突报错指明 Profile（T017）、停用跳过不记历史（最值钱回归）、runNow 无视启用、 ⑦a
 * 并发排队语义（T021）。
 */
class AgentSchedulerTest {

  private static final Profile PROFILE =
      profile("ops-agent", "weather-8am", "0 0 8 * * *", "Asia/Shanghai", "生成今日天气和穿搭建议");

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

  private ScheduledTaskStore store = mock(ScheduledTaskStore.class);

  private AgentScheduler scheduler(
      ThreadPoolTaskScheduler taskScheduler,
      ProfileRegistry registry,
      SessionManager sessionManager,
      AgentService agentService) {
    // 默认：任务启用、执行历史回读返回固定一条（executeInternal 返回值需要持久化 id 的视图）
    when(store.isEnabled(anyString())).thenReturn(true);
    when(store.executions(anyString()))
        .thenReturn(
            List.of(
                new TaskExecutionView(1L, "weather-8am", null, Instant.now(), true, null, 10L)));
    return new AgentScheduler(taskScheduler, registry, sessionManager, agentService, store);
  }

  // ==================== 008 存量（适配 010：id 补入、锁 key 退役为 taskId） ====================

  // ---- 注册参数对（坑四：时区显式；坑一：配置驱动） ----

  @Test
  @DisplayName("坑四：注册参数带配置的 cron 与时区（CronTrigger 显式 TimeZone）")
  void registersWithConfiguredCronAndZone() {
    ThreadPoolTaskScheduler taskScheduler = mock(ThreadPoolTaskScheduler.class);
    ProfileRegistry registry = mock(ProfileRegistry.class);
    when(registry.list()).thenReturn(List.of(PROFILE));
    AgentScheduler scheduler =
        scheduler(taskScheduler, registry, mock(SessionManager.class), mock(AgentService.class));

    scheduler.registerAll();

    ArgumentCaptor<CronTrigger> captor = ArgumentCaptor.forClass(CronTrigger.class);
    verify(taskScheduler).schedule(any(Runnable.class), captor.capture());
    // H3 核实：Spring 6.2 CronTrigger 无公开 getTimeZone()——用 equals（已实现）同时比对 cron 与时区
    assertThat(captor.getValue())
        .isEqualTo(new CronTrigger("0 0 8 * * *", TimeZone.getTimeZone("Asia/Shanghai")));
  }

  // ---- 最值钱之二：上一次还没跑完，本次触发直接跳过（坑二） ----

  @Test
  @DisplayName("最值钱之二：锁被占时本次触发直接跳过、不排队（verify never ×2：process 与 recordExecution）")
  void skipsTriggerWhenLockHeld() throws Exception {
    AgentService agentService = mock(AgentService.class);
    AgentScheduler scheduler =
        scheduler(
            mock(ThreadPoolTaskScheduler.class),
            mock(ProfileRegistry.class),
            mock(SessionManager.class),
            agentService);
    Profile.Schedule sc = PROFILE.schedules().get(0);
    Lock lock = scheduler.lockFor("weather-8am"); // 010：锁 key = taskId（008 派生 key 退役）
    // 实现级明确：ReentrantLock 可重入——同线程 lock() 后再 tryLock() 恒成功（课件骨架同线程写法走不到跳过分支）；
    // 用虚拟线程模拟"调度线程仍持有锁"的生产语义（无自建线程池——测试辅助线程不违反宪法 VII）
    CountDownLatch held = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    Thread holder =
        Thread.ofVirtual()
            .start(
                () -> {
                  lock.lock(); // 模拟上一次还占着锁（不同线程）
                  try {
                    held.countDown();
                    release.await();
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                  } finally {
                    lock.unlock();
                  }
                });
    held.await();
    try {
      scheduler.runOnce(PROFILE, sc);
      verify(agentService, never()).process(any(), any()); // 没有叠加执行
      verify(store, never()).recordExecution(any()); // 跳过不记历史（010）
    } finally {
      release.countDown();
      holder.join();
    }
  }

  // ---- 最值钱之一：任务抛异常，不外抛且锁必须被释放（坑三，二进宫） ----

  @Test
  @DisplayName("最值钱之一：任务抛异常不外抛且锁被释放（二进宫——finally 漏 unlock 只有它能抓住）")
  void exceptionDoesNotEscapeAndLockIsReleased() throws Exception {
    AgentService agentService = mock(AgentService.class);
    when(agentService.process(any(), any())).thenThrow(new RuntimeException("boom"));
    SessionManager sessionManager = mock(SessionManager.class);
    Session session = mock(Session.class);
    when(session.id()).thenReturn("scheduler|scheduler|ops-agent");
    when(sessionManager.getOrCreate(anyString(), anyString(), anyString())).thenReturn(session);
    AgentScheduler scheduler =
        scheduler(
            mock(ThreadPoolTaskScheduler.class),
            mock(ProfileRegistry.class),
            sessionManager,
            agentService);
    Profile.Schedule sc = PROFILE.schedules().get(0);

    assertDoesNotThrow(() -> scheduler.runOnce(PROFILE, sc)); // 调度器不死
    assertDoesNotThrow(() -> scheduler.runOnce(PROFILE, sc)); // 再触发一次
    verify(agentService, times(2)).process(any(), any()); // 能进来——锁真的放了，没有永久卡死
    verify(store, times(2)).recordExecution(any()); // 两次失败都落账（session 为 null 的 NPE 也算失败路径）
  }

  // ---- 会话身份（宪法 VIII：三元组固定 scheduler） ----

  @Test
  @DisplayName("宪法 VIII：会话三元组固定 (scheduler, scheduler, profileName)，两次触发同一 Session")
  void usesSchedulerTripleSession() {
    SessionManager sessionManager = mock(SessionManager.class);
    Session session = mock(Session.class);
    when(sessionManager.getOrCreate(eq("scheduler"), eq("scheduler"), eq("ops-agent")))
        .thenReturn(session);
    AgentScheduler scheduler =
        scheduler(
            mock(ThreadPoolTaskScheduler.class),
            mock(ProfileRegistry.class),
            sessionManager,
            mock(AgentService.class));
    Profile.Schedule sc = PROFILE.schedules().get(0);

    scheduler.runOnce(PROFILE, sc);
    scheduler.runOnce(PROFILE, sc);

    verify(sessionManager, times(2)).getOrCreate("scheduler", "scheduler", "ops-agent");
  }

  @Test
  @DisplayName("到点触发：消息内容交给 AgentService（与 CLI/Web 完全同一入口）")
  void processesMessageThroughAgentService() throws Exception {
    SessionManager sessionManager = mock(SessionManager.class);
    Session session = mock(Session.class);
    when(sessionManager.getOrCreate(anyString(), anyString(), anyString())).thenReturn(session);
    AgentService agentService = mock(AgentService.class);
    AgentScheduler scheduler =
        scheduler(
            mock(ThreadPoolTaskScheduler.class),
            mock(ProfileRegistry.class),
            sessionManager,
            agentService);
    Profile.Schedule sc = PROFILE.schedules().get(0);

    scheduler.runOnce(PROFILE, sc);

    verify(agentService).process(session, "生成今日天气和穿搭建议");
  }

  // ---- T004（US2）：锁占跳过的日志证据 ----

  @Test
  @DisplayName("坑二：锁占跳过时记 info 日志（含「跳过本次触发」与 taskId）")
  void skipLogsInfoWithTaskKey() throws Exception {
    AgentScheduler scheduler =
        scheduler(
            mock(ThreadPoolTaskScheduler.class),
            mock(ProfileRegistry.class),
            mock(SessionManager.class),
            mock(AgentService.class));
    Profile.Schedule sc = PROFILE.schedules().get(0);
    Logger logger = (Logger) LoggerFactory.getLogger(AgentScheduler.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    CountDownLatch held = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    Thread holder =
        Thread.ofVirtual()
            .start(
                () -> {
                  scheduler.lockFor("weather-8am").lock();
                  try {
                    held.countDown();
                    release.await();
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                  } finally {
                    scheduler.lockFor("weather-8am").unlock();
                  }
                });
    held.await();
    try {
      scheduler.runOnce(PROFILE, sc);
      assertThat(appender.list)
          .anySatisfy(
              event -> {
                assertThat(event.getLevel()).isEqualTo(Level.INFO);
                assertThat(event.getFormattedMessage()).contains("跳过本次触发").contains("weather-8am");
              });
    } finally {
      release.countDown();
      logger.detachAppender(appender);
      holder.join();
    }
  }

  // ---- T005（US3）：失败日志口径（NFR-3，⑧a 澄清） ----

  @Test
  @DisplayName("坑三 / NFR-3：失败记 error 日志含 taskId 与异常（两行日志口径不变）")
  void failureLogsErrorWithTaskKeyOnly() throws Exception {
    AgentService agentService = mock(AgentService.class);
    when(agentService.process(any(), any())).thenThrow(new RuntimeException("boom"));
    SessionManager sessionManager = mock(SessionManager.class);
    Session session = mock(Session.class);
    when(session.id()).thenReturn("scheduler|scheduler|ops-agent");
    when(sessionManager.getOrCreate(anyString(), anyString(), anyString())).thenReturn(session);
    AgentScheduler scheduler =
        scheduler(
            mock(ThreadPoolTaskScheduler.class),
            mock(ProfileRegistry.class),
            sessionManager,
            agentService);
    Profile.Schedule sc = PROFILE.schedules().get(0);
    Logger logger = (Logger) LoggerFactory.getLogger(AgentScheduler.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      assertDoesNotThrow(() -> scheduler.runOnce(PROFILE, sc)); // 调度器不死
      // 两行日志形态（FindSecBugs CRLF 门禁）：第一行 (String, Object) 带 sanitize 后的 taskId、无异常；第二行常量 +
      // Throwable
      assertThat(appender.list)
          .anySatisfy(
              event -> {
                assertThat(event.getLevel()).isEqualTo(Level.ERROR);
                assertThat(event.getFormattedMessage()).contains("weather-8am");
              })
          .anySatisfy(
              event -> {
                assertThat(event.getLevel()).isEqualTo(Level.ERROR);
                assertThat(event.getFormattedMessage()).isEqualTo("定时任务执行失败详情"); // 常量行带异常栈
                assertThat(event.getThrowableProxy().getClassName())
                    .isEqualTo(RuntimeException.class.getName());
              });
    } finally {
      logger.detachAppender(appender);
    }
  }

  // ---- T006（US4）：注册组——⑦b 非法 zone / 全量注册 / ⑦c 句柄 / zone 空单参 ----

  @Test
  @DisplayName("⑦b：非法 zone 启动报错（拼错时区不静默回退 GMT，001 纪律）")
  void illegalZoneFailsAtRegistration() {
    ThreadPoolTaskScheduler taskScheduler = mock(ThreadPoolTaskScheduler.class);
    ProfileRegistry registry = mock(ProfileRegistry.class);
    when(registry.list())
        .thenReturn(
            List.of(profile("ops-agent", "ops-agent-job", "0 0 8 * * *", "Asia/Shangha", "msg")));
    AgentScheduler scheduler =
        scheduler(taskScheduler, registry, mock(SessionManager.class), mock(AgentService.class));

    assertThatThrownBy(scheduler::registerAll)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("ops-agent")
        .hasMessageContaining("Asia/Shangha");
  }

  @Test
  @DisplayName("坑一：多 Profile 多 schedules 全量注册（schedule 调用次数 = schedules 总数）")
  void registersAllSchedulesAcrossProfiles() {
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
        scheduler(taskScheduler, registry, mock(SessionManager.class), mock(AgentService.class));

    scheduler.registerAll();

    verify(taskScheduler, times(2)).schedule(any(Runnable.class), any(CronTrigger.class));
    assertThat(scheduler.scheduledTaskCount()).isEqualTo(2); // ⑦c：句柄已登记
  }

  @Test
  @DisplayName("坑四：zone 空 → 单参 CronTrigger 按系统时区（Profile javadoc 口径）")
  void blankZoneUsesSingleArgCronTrigger() {
    ThreadPoolTaskScheduler taskScheduler = mock(ThreadPoolTaskScheduler.class);
    ProfileRegistry registry = mock(ProfileRegistry.class);
    when(registry.list())
        .thenReturn(List.of(profile("ops-agent", "ops-agent-job", "0 0 8 * * *", null, "msg")));
    AgentScheduler scheduler =
        scheduler(taskScheduler, registry, mock(SessionManager.class), mock(AgentService.class));

    scheduler.registerAll();

    ArgumentCaptor<CronTrigger> captor = ArgumentCaptor.forClass(CronTrigger.class);
    verify(taskScheduler).schedule(any(Runnable.class), captor.capture());
    assertThat(captor.getValue()).isEqualTo(new CronTrigger("0 0 8 * * *"));
  }

  // ==================== T017（US1）：登记与持久化 ====================

  @Test
  @DisplayName("US1：registerAll 把任务登记进 store（task_id=frontmatter id、next_run_at 非空）")
  void registerAllRegistersTasksInStore() {
    ThreadPoolTaskScheduler taskScheduler = mock(ThreadPoolTaskScheduler.class);
    ProfileRegistry registry = mock(ProfileRegistry.class);
    when(registry.list()).thenReturn(List.of(PROFILE));
    AgentScheduler scheduler =
        scheduler(taskScheduler, registry, mock(SessionManager.class), mock(AgentService.class));

    scheduler.registerAll();

    ArgumentCaptor<ScheduledTaskView> captor = ArgumentCaptor.forClass(ScheduledTaskView.class);
    verify(store).register(captor.capture(), notNull());
    assertThat(captor.getValue().taskId()).isEqualTo("weather-8am");
    assertThat(captor.getValue().profileName()).isEqualTo("ops-agent");
    assertThat(captor.getValue().cron()).isEqualTo("0 0 8 * * *");
    assertThat(captor.getValue().zone()).isEqualTo("Asia/Shanghai");
    assertThat(captor.getValue().message()).isEqualTo("生成今日天气和穿搭建议");
  }

  @Test
  @DisplayName("US1：id 冲突注册启动报错且指明冲突的两个 Profile（⑦ P2）")
  void duplicateTaskIdFailsWithProfileNames() {
    ThreadPoolTaskScheduler taskScheduler = mock(ThreadPoolTaskScheduler.class);
    ProfileRegistry registry = mock(ProfileRegistry.class);
    when(registry.list())
        .thenReturn(
            List.of(
                profile("weather-agent", "same-id", "0 0 8 * * *", null, "天气"),
                profile("news-agent", "same-id", "0 0 9 * * *", null, "新闻")));
    AgentScheduler scheduler =
        scheduler(taskScheduler, registry, mock(SessionManager.class), mock(AgentService.class));

    assertThatThrownBy(scheduler::registerAll)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("same-id")
        .hasMessageContaining("weather-agent")
        .hasMessageContaining("news-agent");
    verify(store, times(1)).register(any(), any()); // 首个登记后冲突即止，第二个不落
  }

  @Test
  @DisplayName("US1：执行成功 → recordExecution(success=true)（taskId/sessionId/duration 齐备）")
  void successfulRunRecordsExecution() {
    SessionManager sessionManager = mock(SessionManager.class);
    Session session = mock(Session.class);
    when(session.id()).thenReturn("scheduler|scheduler|ops-agent");
    when(sessionManager.getOrCreate(anyString(), anyString(), anyString())).thenReturn(session);
    AgentScheduler scheduler =
        scheduler(
            mock(ThreadPoolTaskScheduler.class),
            mock(ProfileRegistry.class),
            sessionManager,
            mock(AgentService.class));

    scheduler.runOnce(PROFILE, PROFILE.schedules().get(0));

    ArgumentCaptor<TaskExecutionView> captor = ArgumentCaptor.forClass(TaskExecutionView.class);
    verify(store).recordExecution(captor.capture());
    assertThat(captor.getValue().taskId()).isEqualTo("weather-8am");
    assertThat(captor.getValue().sessionId()).isEqualTo("scheduler|scheduler|ops-agent");
    assertThat(captor.getValue().success()).isTrue();
    assertThat(captor.getValue().errorMessage()).isNull();
    assertThat(captor.getValue().durationMs()).isNotNull();
  }

  @Test
  @DisplayName("US1：执行失败 → recordExecution(success=false, error_message 人可读非堆栈)（宪法 V 成败都记）")
  void failedRunRecordsExecution() {
    AgentService agentService = mock(AgentService.class);
    when(agentService.process(any(), any())).thenThrow(new RuntimeException("boom"));
    SessionManager sessionManager = mock(SessionManager.class);
    Session session = mock(Session.class);
    when(session.id()).thenReturn("scheduler|scheduler|ops-agent");
    when(sessionManager.getOrCreate(anyString(), anyString(), anyString())).thenReturn(session);
    AgentScheduler scheduler =
        scheduler(
            mock(ThreadPoolTaskScheduler.class),
            mock(ProfileRegistry.class),
            sessionManager,
            agentService);

    scheduler.runOnce(PROFILE, PROFILE.schedules().get(0));

    ArgumentCaptor<TaskExecutionView> captor = ArgumentCaptor.forClass(TaskExecutionView.class);
    verify(store).recordExecution(captor.capture());
    assertThat(captor.getValue().success()).isFalse();
    assertThat(captor.getValue().errorMessage()).isEqualTo("boom"); // 人可读 message，非堆栈
  }

  // ==================== T021（US2）：停用 / runNow / ⑦a 并发 ====================

  @Test
  @DisplayName("US2 最值钱回归：停用后到点不触发且不记历史（verify never ×2）")
  void disabledTaskSkipsWithoutHistory() {
    AgentService agentService = mock(AgentService.class);
    AgentScheduler scheduler =
        scheduler(
            mock(ThreadPoolTaskScheduler.class),
            mock(ProfileRegistry.class),
            mock(SessionManager.class),
            agentService);
    when(store.isEnabled("weather-8am")).thenReturn(false); // 在 helper 默认 stub 之后覆盖

    scheduler.runOnce(PROFILE, PROFILE.schedules().get(0));

    verify(agentService, never()).process(any(), any()); // 不执行
    verify(store, never()).recordExecution(any()); // 不记历史（启用检查在记历史之前）
  }

  @Test
  @DisplayName("US2：runNow 无视启用状态照常执行并记历史")
  void runNowIgnoresDisabled() {
    ThreadPoolTaskScheduler taskScheduler = mock(ThreadPoolTaskScheduler.class);
    ProfileRegistry registry = mock(ProfileRegistry.class);
    when(registry.list()).thenReturn(List.of(PROFILE));
    SessionManager sessionManager = mock(SessionManager.class);
    Session session = mock(Session.class);
    when(session.id()).thenReturn("scheduler|scheduler|ops-agent");
    when(sessionManager.getOrCreate(anyString(), anyString(), anyString())).thenReturn(session);
    AgentScheduler scheduler =
        scheduler(taskScheduler, registry, sessionManager, mock(AgentService.class));
    scheduler.registerAll();
    when(store.isEnabled("weather-8am")).thenReturn(false); // 停用状态——runNow 仍应无视

    TaskExecutionView result = scheduler.runNow("weather-8am");

    assertThat(result.taskId()).isEqualTo("weather-8am");
    verify(store).recordExecution(any()); // 照常执行并落账
  }

  @Test
  @DisplayName("US2：runNow 对未注册任务明确报错（不静默）")
  void runNowRejectsUnknownTask() {
    AgentScheduler scheduler =
        scheduler(
            mock(ThreadPoolTaskScheduler.class),
            mock(ProfileRegistry.class),
            mock(SessionManager.class),
            mock(AgentService.class));

    assertThatThrownBy(() -> scheduler.runNow("ghost"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("ghost");
  }

  @Test
  @DisplayName("⑦a 并发回归：到点先占锁 → runNow 阻塞排队等锁后执行（同锁同入口，串行不双跑）")
  void runNowQueuesBehindCronTrigger() throws Exception {
    AgentService agentService = mock(AgentService.class);
    ThreadPoolTaskScheduler taskScheduler = mock(ThreadPoolTaskScheduler.class);
    ProfileRegistry registry = mock(ProfileRegistry.class);
    when(registry.list()).thenReturn(List.of(PROFILE));
    SessionManager sessionManager = mock(SessionManager.class);
    Session session = mock(Session.class);
    when(session.id()).thenReturn("scheduler|scheduler|ops-agent");
    when(sessionManager.getOrCreate(anyString(), anyString(), anyString())).thenReturn(session);
    AgentScheduler scheduler = scheduler(taskScheduler, registry, sessionManager, agentService);
    scheduler.registerAll();

    CountDownLatch held = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(1);
    Thread holder =
        Thread.ofVirtual()
            .start(
                () -> {
                  scheduler.lockFor("weather-8am").lock(); // 模拟到点触发仍在执行
                  try {
                    held.countDown();
                    release.await();
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                  } finally {
                    scheduler.lockFor("weather-8am").unlock();
                  }
                });
    held.await();
    Thread runner =
        Thread.ofVirtual()
            .start(
                () -> {
                  scheduler.runNow("weather-8am"); // lock() 阻塞排队（S5 F1 口径）
                  done.countDown();
                });
    Thread.sleep(100); // 给 runNow 到达锁的时间
    verify(agentService, never()).process(any(), any()); // 排队中未执行——不双跑
    release.countDown();
    done.await();
    verify(agentService, times(1)).process(any(), any()); // 拿到锁后执行恰一次
    runner.join();
    holder.join();
  }

  @Test
  @DisplayName("⑦a 并发回归：runNow 先占锁 → 到点触发 tryLock 失败直接跳过（不排队）")
  void cronTriggerSkipsWhenRunNowHoldsLock() throws Exception {
    AgentService agentService = mock(AgentService.class);
    ThreadPoolTaskScheduler taskScheduler = mock(ThreadPoolTaskScheduler.class);
    ProfileRegistry registry = mock(ProfileRegistry.class);
    when(registry.list()).thenReturn(List.of(PROFILE));
    SessionManager sessionManager = mock(SessionManager.class);
    Session session = mock(Session.class);
    when(session.id()).thenReturn("scheduler|scheduler|ops-agent");
    when(sessionManager.getOrCreate(anyString(), anyString(), anyString())).thenReturn(session);
    AgentScheduler scheduler = scheduler(taskScheduler, registry, sessionManager, agentService);
    scheduler.registerAll();

    CountDownLatch held = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(1);
    Thread holder =
        Thread.ofVirtual()
            .start(
                () -> {
                  scheduler.lockFor("weather-8am").lock(); // 模拟 runNow 在执行
                  try {
                    held.countDown();
                    release.await();
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                  } finally {
                    scheduler.lockFor("weather-8am").unlock();
                  }
                });
    held.await();
    Thread runner =
        Thread.ofVirtual()
            .start(
                () -> {
                  scheduler.runNow("weather-8am");
                  done.countDown();
                });
    // runNow 先拿到锁后，到点触发到达 → 直接跳过
    try {
      scheduler.runOnce(PROFILE, PROFILE.schedules().get(0));
      verify(agentService, never()).process(any(), any()); // 到点跳过
      verify(store, never()).recordExecution(any()); // 跳过不记历史
    } finally {
      release.countDown();
      done.await();
      runner.join();
      holder.join();
    }
  }

  @Test
  @DisplayName("NFR-003：失败路径 error_message 人可读（异常 message 原文，非堆栈）")
  void failureErrorMessageIsReadable() {
    AgentService agentService = mock(AgentService.class);
    when(agentService.process(any(), any())).thenThrow(new RuntimeException("域名不在白名单内: evil.com"));
    SessionManager sessionManager = mock(SessionManager.class);
    Session session = mock(Session.class);
    when(session.id()).thenReturn("scheduler|scheduler|ops-agent");
    when(sessionManager.getOrCreate(anyString(), anyString(), anyString())).thenReturn(session);
    AgentScheduler scheduler =
        scheduler(
            mock(ThreadPoolTaskScheduler.class),
            mock(ProfileRegistry.class),
            sessionManager,
            agentService);

    scheduler.runOnce(PROFILE, PROFILE.schedules().get(0));

    ArgumentCaptor<TaskExecutionView> captor = ArgumentCaptor.forClass(TaskExecutionView.class);
    verify(store).recordExecution(captor.capture());
    assertThat(captor.getValue().errorMessage()).isEqualTo("域名不在白名单内: evil.com");
    assertThat(captor.getValue().errorMessage()).doesNotContain("at com.");
  }
}
