package com.axion.core;

import com.axion.storage.ScheduledTaskStore;
import com.axion.storage.ScheduledTaskView;
import com.axion.storage.TaskExecutionView;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.zone.ZoneRulesException;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.scheduling.support.CronTrigger;

/**
 * 定时任务调度器——第三种触发源（钟推，宪法 VIII）：到点自动拼一条消息，交给 {@link AgentService}（与 CLI/Web 完全同一入口， ReActLoop
 * 不感知消息从哪个入口来）。
 *
 * <p>职责划窄（课件 25 §二）：只干"到点拼消息交 AgentService"一件事——消息说什么话是 AGENT.md 的事、怎么处理是 ReActLoop 的事、
 * 多实例归属是扩展阶段分布式协调的事。调度机制用 Spring {@link ThreadPoolTaskScheduler} + {@link CronTrigger} 动态注册
 * （坑一：不用 @Scheduled 写死——触发规则按 Profile 配置动态生成）。
 *
 * <p>007 实施前优化（修订说明 ⑦）：
 *
 * <ul>
 *   <li>⑦a 装配处 {@code setPoolSize(4)}——默认单线程下同步阻塞的长 ReAct 会跨任务互相拖累（防重叠锁只管同任务）
 *   <li>⑦b zone 非空先经 {@code ZoneId.of} 校验——非法时区启动明确报错（001 纪律：{@code TimeZone.getTimeZone} 对拼错值 静默回退
 *       GMT 会「到点不触发」），不静默
 *   <li>⑦c {@code schedule} 返回句柄存 {@link #scheduledTasks}——28 节启用停用/扩展阶段重调度的 cancel 前置
 * </ul>
 *
 * <p>010-scheduler-mgmt 升级：锁 key 与 task_id 直接用 {@code Profile.Schedule.id}（拍板 A，008 派生 key {@code
 * profileName|cron|message} 退役——改 message 换任务身份的断链风险解除）；注册时把任务登记进 {@link ScheduledTaskStore} （含算出的
 * next_run_at，状态与历史重启不丢）；执行入口拆「看启用状态 → 真正执行」——停用到点直接跳过、不执行、不记历史； 每次真正执行成功失败都写执行历史（宪法 V 同源）；{@link
 * #runNow} 管理台立即执行（无视启用状态，与到点触发同锁同入口——⑦a 并发不双跑，runNow 拿不到锁按排队语义，S5 analyze F1 口径）。
 *
 * <p>无组件注解纯类（G4-C1）——由装配处（CliAgentConfiguration）显式 @Bean 并在方法体内调 {@link #registerAll()}。
 */
public class AgentScheduler {

  private static final Logger LOG = LoggerFactory.getLogger(AgentScheduler.class);

  private final ThreadPoolTaskScheduler taskScheduler;
  private final ProfileRegistry profileRegistry;
  private final SessionManager sessionManager;
  private final AgentService agentService;
  private final ScheduledTaskStore store;
  private final ConcurrentMap<String, Lock> taskLocks = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, ScheduledFuture<?>> scheduledTasks =
      new ConcurrentHashMap<>();
  private final ConcurrentMap<String, Registration> registrations = new ConcurrentHashMap<>();

  /** taskId → 注册信息（runNow 按 taskId 找回 Profile 与 Schedule——008 的 scheduledTasks 只存句柄，010 补齐）。 */
  private record Registration(Profile profile, Profile.Schedule schedule) {}

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "ThreadPoolTaskScheduler/ProfileRegistry/SessionManager/AgentService/ScheduledTaskStore"
              + " 为装配处注入的单例（只读使用、不暴露引用），004 WebhookNotifyAdapter 同款先例")
  public AgentScheduler(
      ThreadPoolTaskScheduler taskScheduler,
      ProfileRegistry profileRegistry,
      SessionManager sessionManager,
      AgentService agentService,
      ScheduledTaskStore store) {
    this.taskScheduler = taskScheduler;
    this.profileRegistry = profileRegistry;
    this.sessionManager = sessionManager;
    this.agentService = agentService;
    this.store = store;
  }

  /** 启动注册：扫所有 Profile 的 schedules 逐条动态注册（坑一——不用 @Scheduled 写死），并登记进 scheduled_tasks。 */
  public void registerAll() {
    for (Profile profile : profileRegistry.list()) {
      registerProfile(profile);
    }
  }

  /**
   * 单 Profile 注册全部 schedules（011-plugin-agent FR-4：registerAll 循环体抽出——30 节运行时注册与启动扫描同一段代码）： id 冲突查
   * registrations（报错文案与 010 一致）→ 登记 scheduled_tasks（含 next_run_at）→ 注册 cron 触发 → 句柄入
   * scheduledTasks。
   */
  public void registerProfile(Profile profile) {
    for (Profile.Schedule sc : profile.schedules()) {
      String taskId = sc.id();
      // id 全局唯一（跨 Profile 冲突启动报错，指明冲突的 Profile——两个 Agent 同 id 时运营方猜不出来，⑦ P2）
      Registration previous = registrations.get(taskId);
      if (previous != null) {
        throw new IllegalStateException(
            "定时任务 id 冲突: "
                + taskId
                + "（Profile "
                + previous.profile().name()
                + " 与 "
                + profile.name()
                + "）");
      }
      // ⑦b：非法 zone/cron 在触发构造处启动报错（008 口径），先于登记
      CronTrigger trigger =
          sc.zone() == null || sc.zone().isBlank()
              ? new CronTrigger(sc.cron()) // zone 缺省按系统时区（Profile javadoc）
              : new CronTrigger(sc.cron(), validatedZone(profile.name(), sc.zone()));
      store.register(toView(profile, sc), nextRunAfter(sc.cron(), sc.zone(), Instant.now()));
      ScheduledFuture<?> future = taskScheduler.schedule(() -> runOnce(profile, sc), trigger);
      if (future != null) { // 真实调度器恒非空；mock 返回 null 时跳过登记（⑦c 句柄只对真实注册有意义）
        scheduledTasks.put(taskId, future);
      }
      registrations.put(taskId, new Registration(profile, sc));
    }
  }

  /** 到点触发入口：先看启用状态（停用直接跳过、不执行、不记历史——最值钱回归），再进真正执行。 */
  public void runOnce(Profile profile, Profile.Schedule sc) {
    if (!store.isEnabled(sc.id())) {
      return;
    }
    try {
      executeInternal(profile, sc, false);
    } catch (Exception e) {
      // 执行记录写入失败等 store 异常——调度器不死（008 坑三延续；process 异常在 executeInternal 内已消化）
      LOG.error("定时任务触发处理异常: {}", sanitize(sc.id()));
      LOG.error("定时任务触发处理异常详情", e);
    }
  }

  /** 管理台立即执行：手动触发一次、不等 cron、无视启用状态（⑦a 与到点触发同一把锁、同一个 executeInternal——并发不双跑）。 */
  public TaskExecutionView runNow(String taskId) {
    Registration registration = registrations.get(taskId);
    if (registration == null) {
      throw new IllegalStateException("定时任务未注册: " + taskId);
    }
    return executeInternal(registration.profile(), registration.schedule(), true);
  }

  /**
   * 真正执行（runOnce 与 runNow 共用——⑦a 同锁同入口）：
   *
   * <ul>
   *   <li>锁策略区分（S5 analyze F1 修正）：到点触发 tryLock 失败直接跳过（008 坑二：不排队、不并行）；runNow lock() 阻塞排队——POST /run
   *       必须返回执行记录，跳过则无记录可返回（60s 端点超时兜底）
   *   <li>成败都 recordExecution + 任务状态迁移（last_run/last_status/run_count/next_run 由 store 内部完成）
   *   <li>error_message 取最外层异常人可读 message（非堆栈，NFR-003）
   * </ul>
   */
  private TaskExecutionView executeInternal(
      Profile profile, Profile.Schedule sc, boolean waitForLock) {
    String taskId = sc.id();
    Lock lock = lockFor(taskId);
    if (waitForLock) {
      lock.lock();
    } else if (!lock.tryLock()) {
      LOG.info("定时任务仍在执行，跳过本次触发: {}", sanitize(taskId)); // 坑二：不排队、不并行（CRLF 净化，005 先例）
      return null;
    }
    Instant startedAt = Instant.now();
    long beginNanos = System.nanoTime();
    String sessionId = null;
    boolean success = true;
    String errorMessage = null;
    try {
      // 宪法 VIII：channel/user 固定 scheduler——同一 Profile 历次触发复用同一 Session
      Session session = sessionManager.getOrCreate("scheduler", "scheduler", profile.name());
      sessionId = session.id();
      agentService.process(session, sc.message()); // 与 CLI/Web 完全一样的入口
    } catch (Exception e) {
      success = false;
      errorMessage = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
      // 两行日志满足 FindSecBugs CRLF 门禁——带变量的用 (String, Object) 形式（值已 sanitize），
      // 异常栈用常量 + Throwable 双参（AgentLoader 先例；用户可控值不入带 Throwable 的三参重载）
      LOG.error("定时任务执行失败: {}", sanitize(taskId));
      LOG.error("定时任务执行失败详情", e);
    } finally {
      lock.unlock(); // 最值钱之二：锁必须放掉
    }
    long durationMs = (System.nanoTime() - beginNanos) / 1_000_000;
    TaskExecutionView view =
        new TaskExecutionView(
            null, taskId, sessionId, startedAt, success, errorMessage, durationMs);
    try {
      store.recordExecution(view); // 成败都落账 + 状态迁移（宪法 V 同源）
    } catch (Exception storeEx) {
      LOG.error("定时任务执行记录写入失败: {}", sanitize(taskId));
      LOG.error("定时任务执行记录写入失败详情", storeEx);
      throw new IllegalStateException("定时任务执行记录写入失败: " + taskId, storeEx);
    }
    return store.executions(taskId).get(0); // 最新在前——本次记录（持久化后带 id，POST /run 返回体）
  }

  /** 日志参数净化 CR/LF（CRLF 注入防线，CliAgentConfiguration 同款机器可判写法）——锁 key 本身不净化。 */
  private String sanitize(String value) {
    return value.replace('\r', '?').replace('\n', '?');
  }

  /** ⑦b：zone 合法性校验——TimeZone.getTimeZone 对拼错时区静默回退 GMT 会「到点不触发」，按 001 纪律不静默。 */
  private TimeZone validatedZone(String profileName, String zone) {
    try {
      ZoneId.of(zone);
      return TimeZone.getTimeZone(zone);
    } catch (ZoneRulesException e) {
      throw new IllegalStateException("定时任务时区非法: profile=" + profileName + " zone=" + zone, e);
    }
  }

  /** next_run_at 计算（H3 已核实 T001）：公开 API {@code CronExpression.parse(...).next(...)}，zone 空按系统时区。 */
  private Instant nextRunAfter(String cron, String zone, Instant baseline) {
    ZoneId zoneId = zone == null || zone.isBlank() ? ZoneId.systemDefault() : ZoneId.of(zone);
    CronExpression expression = CronExpression.parse(cron);
    if (expression
        == null) { // parse 契约 @Nullable（SpotBugs NP 门禁）——非法 cron 已被先行的 CronTrigger 构造拦截，防御不静默
      throw new IllegalStateException("cron 表达式非法: " + cron);
    }
    ZonedDateTime next = expression.next(ZonedDateTime.ofInstant(baseline, zoneId));
    if (next == null) { // next 契约同为 @Nullable——六段 cron 恒有下一次，防御不静默
      throw new IllegalStateException("cron 无下一次触发时刻: " + cron);
    }
    return next.toInstant();
  }

  private ScheduledTaskView toView(Profile profile, Profile.Schedule sc) {
    return new ScheduledTaskView(
        sc.id(), profile.name(), sc.cron(), sc.zone(), sc.message(), true, null, null, null, 0);
  }

  /** 测试钩子（课件 25 §四骨架同款）：按 taskId 取任务锁——锁占跳过回归用。 */
  Lock lockFor(String taskKey) {
    return taskLocks.computeIfAbsent(taskKey, k -> new ReentrantLock());
  }

  /** 测试钩子（⑦c）：已登记句柄数——28 节启停/重调度前置的机器证据。 */
  int scheduledTaskCount() {
    return scheduledTasks.size();
  }
}
