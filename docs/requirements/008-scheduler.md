# 定时任务模块设计文档

> 需求编号：008-scheduler | 对应课件第 25 节《定时任务模块 原理解析、实现与代码讲解》（三种触发源之钟推，宪法 VIII；Demo 一/二的地基，31 节验收）
> 文档依据：`docs/TechnicalSolution.md` §8.5（权威设计源）、`docs/DemandAnalysis.md` §13（Demo 一/二钟推验收硬条件）、CLAUDE.md 原则八 + 核心数据模型 schedules 示例；AiProgrammingGuide / IndustryResearch 无独立章节（如实标注）
>
> 修订说明（2026-09-07）：① **课件口径（用户拍板）**：第 25 节新版 PDF 中文已可机器提取（PyMuPDF，方法见 007 修订说明 ① 实录）——7 页 2335 汉字全文复核，叙述与引用以新版 PDF 为准（本节无章节号错位，一/二/三/四/五结构一致）。② **形态机械适配**（005/007 拍板延续）：课件 `@Component`/`@PostConstruct` 形态 → G4-C1 无组件注解纯类 + 装配处显式 `@Bean`（装配处顺带显式调 `registerAll()`，替代 @PostConstruct）。③ **锁 key 形态适配（用户拍板 B，2026-09-07）**：课件 `ScheduleConfig` 带 `id` 字段（锁 key 用它），而 002 已交付的 `Profile.Schedule` 只有 cron/zone/message 三字段无 id（CLAUDE.md frontmatter 三键已定）——**不改前序字面量**，AgentScheduler 内部派生锁 key `profileName|cron|message`（进程内锁 key 足够稳定；28 节 task_id 来源必须重议——见 ⑦d 与跨节契约，不沿用本派生规则）。④ **方法名适配**：课件骨架 `profileRegistry.all()` → 项目现状 `ProfileRegistry.list()`（002 已交付字面量，不改）。⑤ **图名处置**：技术方案 §8.5 既有图已占用 `docs-scheduler.svg`，本节设计文档图命名 `docs-scheduler-flow.svg`（不覆盖技术方案既有图）。⑥ **边界（技术方案 §8.5 明文）**：任务状态持久化与管理（两张表 + ScheduledTaskStore + 四端点）归**第 28 节**；分布式协调/失败重试/告警归扩展阶段（课件「有几样先别做」）。⑦ **实施前优化（2026-09-07 三维修正分析：稳定性/扩展性/可靠性）**：a. **调度线程池 `setPoolSize(4)`**——`ThreadPoolTaskScheduler` 默认单线程，同步阻塞的长 ReAct 任务会占住唯一线程、拖累其他任务的到点触发（跨任务互斥——课件与技术方案只讲同任务锁防重叠，未提跨任务阻塞，四文档之外盲点）；b. **zone 非法 → 启动报错**——`TimeZone.getTimeZone` 对拼错时区静默回退 GMT，会「到点不触发」（陷阱表变体）；按 001 ConfigLoader 纪律「非法配置不静默」补 zone 校验；c. **保存 `ScheduledFuture` 句柄**——28 节「启用停用」与扩展阶段重调度需要 cancel/重排，现在丢弃返回值会导致 28 节返工 registerAll；存 Map 一行纯预留；d. **28 节 task_id 派生重议注记**——锁 key 含 message（自然语言、易变），28 节照搬会执行历史断链，见「跨节契约」。⑧ **实施中暴露的两处口径修正（2026-09-08，S6 实证）**：a. **NFR-3 澄清**——拍板 B 的派生 key 含 message，日志带 taskKey 即带 message；message 属 frontmatter 运营配置（非运行时用户可控值），CRLF 纪律只管运行时输入，key 进日志可接受（NFR-3 已同步）；b. **Spring 6 cron 六字段**——H3 实测 `CronExpression.parse` 只接受 6 段（含秒），CLAUDE.md 核心数据模型示例 `0 8 * * *`（五段）是示意——实际配置须 `0 0 8 * * *`；CLAUDE.md 示例修正提请用户拍板（宪法级文件，本节不擅改）。c. **课件锁测试骨架修正**——ReentrantLock 可重入，同线程 lock() 后 tryLock() 恒成功（课件骨架同线程写法走不到跳过分支）；测试须用虚拟线程模拟调度线程持有（生产语义）。

## 背景与价值

Provider、ReAct、CLI、Notify、Tool、Memory、Sandbox 都交付了——Agent 能被喂话、会想、会动手、会往外推消息、记得住事、干活有安全边界。定时任务补的是基础能力的最后一个缺口：**Agent 能不能不用人喂话，自己到点干活**（课件 §一）。

定时任务不是一种新能力，是**第三种触发源**：CLI 是人推、Web 是人推（换个入口）、定时任务是钟推——到点了，系统自己拼一条消息，喂给跟 CLI/Web 完全一样的处理入口。ReActLoop 怎么想、Tool 怎么执行、Provider 怎么调模型，**一个字都不用改**（课件 §一、宪法 VIII）。这正是「核心引擎稳，加一个新的入口不用动它一行代码」的兑现（课件结语）。

它还是两个验收 Demo 的地基：每日天气、每日科技日报（31 节）都是"到点自动跑"的 Agent——Skill/AGENT.md 定义了"做什么"，缺的就是这节要补的"到点了谁来喊它一声"（课件 §一、需求文档 §13 两 Demo 钟推硬条件）。

模块职责划窄（课件 §二）：**只干一件事——到点了，拼一条消息，交给 AgentService**。消息里说什么话，是 Profile/AGENT.md 的事；消息交上去怎么处理，是 ReActLoop 的事。别让它膨胀成一个小型工作流引擎。别自己写调度器——Spring 自带 `TaskScheduler` 支持标准 cron 与动态注册，要写的只是薄薄一层，把"Profile 里配的定时规则"接到 Spring 的调度能力上（Provider 节"协议转换不自己造"同款原则）。

## 用户场景

**场景一（本节验收场景）：到点自动触发完整 ReAct 循环**——Agent 的 `AGENT.md` frontmatter 配了 `schedules`（cron + zone + message），进程启动注册后到点自动拼消息调 `AgentService.process`，跑完完整 ReAct 循环，`llm_calls`/`tool_invocations` 照常落账——跟人推的一次对话完全一样记账，不为钟推新设任何审计逻辑（课件 §四人工项 + 技术方案 §8.5 失败处理）。

**场景二：上一次还没跑完，本次触发直接跳过**——ReAct 循环跑得比调度间隔还长（每分钟触发但上一次未结束）：同一任务拿不到本地锁，本次触发跳过不排队、不并行跑两份（坑二，课件 §三）。

**场景三：任务失败，调度器不死**——某次定时任务内部抛异常：只记日志，异常不外抛，锁在 finally 里释放，其他任务的下一次触发不受影响；失败的那次调用依然走 AgentService 内部完整审计（坑三，课件 §三/§四最值钱之一）。

**场景四：时区显式，服务器时区不替用户做主**——用户以为的"早上 9 点"必须按配置的 zone 触发，不能全靠服务器系统时区约定俗成（坑四，课件 §三）。

## 功能需求

> 从课件第 25 节与技术方案 §8.5 提炼：交付物列是本节对外概念的白名单，清单之外的新增对外概念必须停下报告。

| 编号 | 需求 | 交付物（落位模块） | 来源 |
|------|------|-------------------|------|
| FR-1 | **`AgentScheduler`（axion-core，无组件注解纯类）**：`registerAll()` 扫 `ProfileRegistry.list()` 的每个 Profile 的 `schedules` 逐条 `taskScheduler.schedule(() -> runOnce(profile, sc), new CronTrigger(cron, zone))` 动态注册——**不用静态 `@Scheduled`**（cron 写死在注解里改一次就要重编译，不符合"配置即 Agent"，坑一）；zone 为空 → 单参 `CronTrigger(cron)` 按系统时区（Profile javadoc 口径「zone 缺省按系统时区」）；**zone 非空先校验**——`ZoneId.of(zone)` 非法抛 ZoneRulesException → 包装成带 profile/zone 的启动报错（⑦b：001 纪律「非法配置不静默」，杜绝 `TimeZone.getTimeZone` 静默回退 GMT 的「到点不触发」）；**`schedule` 返回值存 `Map<String, ScheduledFuture<?>>`**（⑦c 预留：28 节启用停用/扩展阶段重调度的 cancel 句柄，一行纯预留） | `AgentScheduler`（axion-core，com.axion.core）+ 装配改造（见 FR-5） | 课件 §三 坑一；技术方案 §8.5；⑦b/⑦c |
| FR-2 | **`runOnce` 重叠防护（坑二）**：按任务派生 key（`profileName\|cron\|message`，拍板 B）`computeIfAbsent` 每任务一把 `ReentrantLock`；`tryLock` 失败 → log 跳过本次返回（不排队、不并行跑两份）；`finally` 里 `unlock`——成功失败锁必须放掉，否则任务永久"卡住"（课件 §四最值钱之二） | `AgentScheduler.runOnce` | 课件 §三 坑二/§四；技术方案 §8.5 并发控制 |
| FR-3 | **失败隔离（坑三）**：`runOnce` 内 `catch (Exception e)` → `log.error`（含任务派生 key + 异常栈，**不带 message 内容**——NFR-3 口径）→ 不外抛、调度器不崩、不影响其他任务；审计零新增——`agentService.process` 内部照常落 `llm_calls`/`tool_invocations`（007 FR-7 同款零新增） | `AgentScheduler.runOnce` | 课件 §三 坑三/§四；技术方案 §8.5 失败处理 |
| FR-4 | **会话身份（宪法 VIII）**：`sessionManager.getOrCreate("scheduler", "scheduler", profileName)`——同一 Profile 历次定时触发复用同一 Session，对话历史自然累积、靠 `max_history_turns` 截断兜底，不为钟推新设任何概念 | `AgentScheduler.runOnce` | 技术方案 §8.5 会话身份；课件 §三 骨架 |
| FR-5 | **装配（003 交付物改造点）**：`CliAgentConfiguration` 新增 `ThreadPoolTaskScheduler` @Bean（`new` + **`setPoolSize(4)`** + `initialize()`；容器关闭自动 shutdown——ExecutorConfigurationSupport 的 DisposableBean 语义）+ `AgentScheduler` @Bean（构造注入 taskScheduler/ProfileRegistry/SessionManager/AgentService）并在方法体内显式调 `registerAll()`（替代 @PostConstruct，②）；**`setPoolSize(4)` 是 ⑦a 修复**——默认单线程下同步阻塞的长 ReAct 会占住唯一调度线程、跨任务互相拖累（防重叠锁只管同任务，不管跨任务互斥）；池大小 2~4 即可（任务体同步、无需大池）；`ThreadPoolTaskScheduler` 是 CLAUDE.md 陷阱表点名组件，不属于「自建线程池」禁条——任务体 `runOnce` 内部全程同步阻塞（宪法 VII） | `CliAgentConfiguration`（axion-cli，003/007 交付物） | 课件 §三 骨架；CLAUDE.md 陷阱表；宪法 VII/VIII；⑦a |
| NFR-1 | 任务体全程同步阻塞调 `AgentService.process`，不引入 Reactor/CompletableFuture；调度线程池由 Spring `ThreadPoolTaskScheduler` 承担（宪法 VII + 陷阱表点名组件） | — | 宪法 VII；CLAUDE.md 陷阱表 |
| NFR-2 | 单任务失败不得拖垮调度器、不得影响其他任务触发（坑三强化）——catch 边界 = runOnce 全方法 | — | 课件 §三 坑三 |
| NFR-3 | 日志口径（002/005 先例，⑧ 澄清）：日志参数只带任务派生 key 与异常，不拼接任何**运行时用户可控值**（CRLF 口径只管运行时输入——聊天内容/工具参数）；派生 key 含 message 属 AGENT.md frontmatter 运营配置（Agent 作者可控、非运行时输入），随 key 进日志可接受 | — | 002 NFR 口径延续；⑧ |

### 核心代码骨架（课件 25 节骨架一致，形态机械适配：无组件注解 + 派生锁 key + 装配处注册）

```java
// axion-core：com.axion.core —— 钟推入口（G4-C1 无组件注解，装配处显式 @Bean 并调 registerAll）
public class AgentScheduler {

  private static final Logger LOG = LoggerFactory.getLogger(AgentScheduler.class);

  private final ThreadPoolTaskScheduler taskScheduler;
  private final ProfileRegistry profileRegistry;
  private final SessionManager sessionManager;
  private final AgentService agentService;
  private final ConcurrentMap<String, Lock> taskLocks = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, ScheduledFuture<?>> scheduledTasks = new ConcurrentHashMap<>(); // ⑦c：28 节启停/重调度的句柄

  public AgentScheduler(ThreadPoolTaskScheduler taskScheduler, ProfileRegistry profileRegistry,
                        SessionManager sessionManager, AgentService agentService) { ... }

  /** 启动注册：扫所有 Profile 的 schedules 逐条动态注册（坑一——不用 @Scheduled 写死）。 */
  public void registerAll() {
    for (Profile profile : profileRegistry.list()) {          // ② 适配：课件 all() → 现状 list()
      for (Profile.Schedule sc : profile.schedules()) {
        String key = taskKey(profile.name(), sc);
        scheduledTasks.put(                                    // ⑦c：句柄存下，28 节零重构
            key,
            taskScheduler.schedule(
                () -> runOnce(profile, sc),
                sc.zone() == null || sc.zone().isBlank()
                    ? new CronTrigger(sc.cron())               // zone 缺省按系统时区（Profile javadoc）
                    : new CronTrigger(sc.cron(), validatedZone(profile.name(), sc.zone()))));
      }
    }
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

  /** 一次触发：拿本地锁 → 拼会话 → 交 AgentService → 记失败日志 → 放锁。 */
  public void runOnce(Profile profile, Profile.Schedule sc) {
    String taskKey = taskKey(profile.name(), sc);              // ③ 拍板 B：profileName|cron|message 派生
    Lock lock = taskLocks.computeIfAbsent(taskKey, k -> new ReentrantLock());
    if (!lock.tryLock()) {
      LOG.info("定时任务仍在执行，跳过本次触发: {}", taskKey);      // 坑二：不排队、不并行
      return;
    }
    try {
      // 宪法 VIII：channel/user 固定 scheduler——同一 Profile 历次触发复用同一 Session
      Session session = sessionManager.getOrCreate("scheduler", "scheduler", profile.name());
      agentService.process(session, sc.message());             // 与 CLI/Web 完全一样的入口
    } catch (Exception e) {
      LOG.error("定时任务执行失败: {}", taskKey, e);              // 坑三：失败不崩调度器（NFR-3 不带 message）
    } finally {
      lock.unlock();                                           // 最值钱之二：锁必须放掉
    }
  }

  private String taskKey(String profileName, Profile.Schedule sc) {
    return profileName + "|" + sc.cron() + "|" + sc.message();
  }
}
```

### 本节交付物清单（Spec-Kit 拆解锚点 / axion-spec 交付清单比对基准）

- **代码**：`AgentScheduler`（axion-core）
- **测试**：`AgentSchedulerTest`（core，四坑 harness + 两个最值钱 + 会话身份）；`CliAgentConfigurationTest` 增补（AgentScheduler bean 存在 + registerAll 无异常装配断言）
- **表**：无新增（scheduled_tasks/task_executions 归 28 节）
- **配置**：无新配置键（`schedules` 字段 002/003 已交付；AGENT.md frontmatter 三键 cron/zone/message 已定）
- **改造点**：`CliAgentConfiguration`（003/007 交付物）新增 ThreadPoolTaskScheduler @Bean + AgentScheduler @Bean + registerAll 显式调用
- **约定**：会话身份固定 `("scheduler", "scheduler", profileName)`；失败只记日志不崩调度器；锁 key 派生规则（拍板 B）；zone 非法启动报错（⑦b，001 纪律）；调度池 poolSize>1（⑦a）

![定时任务全链路：Profile.schedules 三键（002 已交付，cron/zone/message）→ AgentScheduler 启动 registerAll 扫 ProfileRegistry.list() 动态注册 ThreadPoolTaskScheduler + CronTrigger（坑一时区显式）→ 到点 runOnce：派生锁 key tryLock（坑二重叠跳过）→ 会话三元组固定 scheduler/scheduler/profileName（宪法 VIII）→ AgentService.process 与 CLI/Web 同一入口（ReAct/审计零新增）→ 失败 catch 记日志不崩调度器 + finally 放锁（坑三）；两张表与管理端点归 28 节、分布式协调归扩展](../../website/public/images/docs-scheduler-flow.svg)

## 明确不做

> 来源：课件 §二「先别做」/§四「有几样先别做」、技术方案 §8.5「核心阶段 vs 扩展阶段的边界」、宪法 VII/VIII。

- **任务状态持久化与管理（归 28 节）**：`scheduled_tasks`/`task_executions` 两张表、`ScheduledTaskStore` 接口（core 契约 + storage JPA 实现）、`ScheduleApiController` 四端点（列任务/执行历史/立即执行/启用停用）——技术方案 §8.5 明文「第 28 节补齐」；本节只有"到点自动跑"，运营方的查看/补跑/停用要等 28 节
- **运行时增删改 cron 定义**：核心阶段 schedules 定义只能写在 AGENT.md frontmatter，改 cron/新增任务要重启生效（或触发重新加载）；「API 上传 Agent 目录 + 免重启闭环」依赖扩展阶段的目录上传接口与调度运行时接口一起补齐（技术方案 §8.5 边界原文）
- **分布式协调（选主/分布式锁/租约）**：核心阶段单实例，本地锁只解决同一进程内不重叠；多实例下"这个定时任务归哪个实例执行"是扩展阶段跟"状态外置、走向分布式"一起解决的事（课件 §二）
- **失败自动重试 / 失败自动告警**：核心阶段做到"失败不崩、留痕可查"就够（课件 §四）
- **错过触发补跑（misfire）**：进程宕机错过的触发点不补——重启后从下一次触发点正常走（Spring 调度默认语义；实现级明确，扩展阶段调度管理再议）
- **优雅停机（等待进行中任务完成）**：容器关闭时调度线程池默认不等任务完成即 interrupt——进行中的 ReAct 被断、审计记失败留痕即可（进程都要关了，如实注记不做优雅停机）
- **失败日志节流**：每分钟失败的 cron 每分钟一条 error 日志不节流——扩展阶段失败告警时一并做
- **工作流引擎化**：模块只干"到点拼消息交 AgentService"，不做任务编排/依赖/多步 DAG（课件 §二职责划窄）

## 验收标准

### 自动化部分（harness 承载，`mvn clean verify` 全绿即通过）

定时模块的测试诀窍是**别真等时间**：`runOnce` 拆成独立方法直接调它测全部行为逻辑；cron 触发本身是 Spring 的事，只验"注册参数传对了"（课件 §四）。`AgentSchedulerTest` 一个类覆盖四个坑：

| 测试点 | 守住的坑 |
|--------|---------|
| **注册参数对**：mock ThreadPoolTaskScheduler，`ArgumentCaptor` 抓 `schedule(Runnable, Trigger)` 参数——CronTrigger 带上配置的 cron 与时区（zone 非空时 `TimeZone.getTimeZone(zone)` 等价） | 坑四：时区显式；坑一：配置驱动（schedules 来自 Profile 而非代码） |
| **⑦b 非法 zone 启动报错**：Profile 带 `zone=Asia/Shangha`（拼错）→ `registerAll` 抛 IllegalStateException，message 含 profile 名与 zone（001 纪律：不静默回退 GMT） | ⑦b：到点不触发防线 |
| **重叠跳过**：`lockFor(taskKey)` 先占锁 → `runOnce` → `verify(agentService, never()).process(...)`——没叠加执行；日志含"跳过" | 坑二：重叠执行 |
| **异常不外抛 + 锁释放（二进宫）**：`when(agentService.process(...)).thenThrow(RuntimeException)` → `assertDoesNotThrow(runOnce)`（调度器不死）→ **再跑一次** `runOnce` → `verify(agentService, times(2)).process(...)`——第二次能进来 = 锁真的放了（课件原文：光断言不抛异常不够，finally 漏 unlock 的 bug 只有二进宫式断言能抓住） | 坑三：失败隔离 + 最值钱之一 |
| **会话身份**：runOnce 后 `verify(sessionManager).getOrCreate("scheduler", "scheduler", profileName)`——三元组固定；真 SessionManager 下两次 runOnce 拿到同一 Session 实例（002 契约） | 会话身份约定（宪法 VIII） |
| **注册全扫描**：多 Profile 多 schedules 全量注册（schedule 调用次数 = schedules 总数） | 坑一：配置驱动 |
| `CliAgentConfigurationTest` 增补 | `AgentScheduler` bean 存在且为 AgentScheduler 实例；上下文启动无异常（registerAll 在装配时已调用——真实 taskScheduler 空 registry 零注册不炸）；**ThreadPoolTaskScheduler bean 的 `getScheduledThreadPoolExecutor().getCorePoolSize() > 1`**（⑦a：默认单线程会跨任务阻塞的回归钉） | 装配（FR-5）；⑦a |

最值钱的两个回归（课件 §四原文，实现之前写最划算）：

```java
@Test
void 任务抛异常_不外抛且锁必须被释放() {
    when(agentService.process(any(), any())).thenThrow(new RuntimeException("boom"));
    assertDoesNotThrow(() -> scheduler.runOnce(profile, scheduleConfig("task-1"))); // 调度器不死
    scheduler.runOnce(profile, scheduleConfig("task-1"));                          // 再触发一次
    verify(agentService, times(2)).process(any(), any());                          // 能进来——锁真的放了
}

@Test
void 上一次还没跑完_本次触发直接跳过() {
    Lock lock = scheduler.lockFor("task-1");
    lock.lock();                                   // 模拟上一次还占着锁
    try {
        scheduler.runOnce(profile, scheduleConfig("task-1"));
        verify(agentService, never()).process(any(), any());   // 没有叠加执行
    } finally {
        lock.unlock();
    }
}
```

跑法：`mvn test` 日常全跑；全量 `mvn clean verify` 收尾——**全量全绿，不写死用例数**（007 ⑦e 口径延续）。

### 人工部分（做完怎么验）

- **真实到点触发一次**（课件 §五）：schedules 设成"每分钟"（cron `0 * * * * *` 或每 30 秒），到点看到 Agent 自动发起对话、`llm_calls`/`tool_invocations` 有账——cron 触发链路本身只能真等一次（harness 测的是注册参数与 runOnce 行为，不是 Spring 的钟）
- **配置驱动体感**：改 AGENT.md 的 cron 表达式不用重新编译，重启后按新时间跑
- **端到端预演**：完整走一遍"到点自动触发 → 跑完 ReAct 循环 → 留下审计记录"，为 31 节两个定时 Demo 把地基踩实（注意：Demo 的 http_get 域名需在 007 白名单内——`wttr.in` 已就位）

## 依赖与假设

### 前序交付物（已就位，本节直接依赖）

- **002-react**：`Profile.Schedule`（cron/zone/message 三字段嵌套 record，javadoc「zone 缺省按系统时区」）、`ProfileRegistry.list()`、`SessionManager.getOrCreate(channel, userId, profileName)`、`AgentService.process(Session, String)`——四件全部实测就位
- **003-cli**：`CliAgentConfiguration` 装配先例（AgentLoader 解析 AGENT.md frontmatter 的 schedules 已就位）；`Profile` 大构造已含 schedules 参数
- **007-sandbox**：审计零新增先例（FR-7）；Demo 的 HTTP 域名白名单契约（application.yaml 已含 wttr.in）

**现状确认（2026-09-07 实测）**：`Profile.schedules` 字段与三字段 `Schedule` 就位；`ProfileRegistry` 方法名为 `list()`（无 `all()`——④ 适配）；`AgentScheduler` 类不存在（package-info 仅 javadoc 提及）；全仓无 ThreadPoolTaskScheduler/CronTrigger 使用——与文档描述一致，无缺口。

### 前序缺口（H0 依赖检查）

无——依赖四件全部实测就位，本节为纯增量。

### 改造点（经拍板允许修改的前序公共接口）

- **`CliAgentConfiguration`（003/007 交付物）**：新增两个 @Bean（ThreadPoolTaskScheduler + AgentScheduler）+ registerAll 显式调用——只加不改，既有 bean 零改动
- 其余前序公共接口零改动：`Profile`/`Profile.Schedule`/`ProfileRegistry`/`SessionManager`/`AgentService` 全部原样（③ 拍板 B：不加 id 字段）

### 外部依赖与假设

- **零新第三方依赖**：`ThreadPoolTaskScheduler`/`CronTrigger`（spring-context 既有）、`ReentrantLock`/`ConcurrentHashMap`/`TimeZone`（JDK 原生）
- **课件口径（用户拍板 2026-09-07）**：25 节新版 PDF 已提取（PyMuPDF），叙述与骨架以新版 PDF 为准；②③④ 三处形态适配经简报确认 + 拍板 B
- **线程模型口径**：ThreadPoolTaskScheduler 是 CLAUDE.md 陷阱表点名组件（「AgentScheduler 基于 ThreadPoolTaskScheduler + CronTrigger 动态注册」），不属于宪法不变量「自建线程池」禁条；任务体全程同步阻塞（宪法 VII）；调度线程池生命周期随 Spring 容器（DisposableBean 自动 shutdown）
- **跨节契约**：本节交付的 `AgentScheduler` 是 31 节两个定时 Demo 的触发源——Demo 的 agent 配置走 `AGENT.md` frontmatter `schedules` 三键，不改本节代码。**⑦d 前置注记（28 节必须重议，用户点名 2026-09-07）**：当前锁 key = `profileName|cron|message`（拍板 B）——**message 是自然语言内容，用户改一个字、重启后 key 即漂移**；28 节 `task_id` 若照搬此派生规则，同一逻辑任务的执行历史将断链（`task_executions.task_id` 对不上）。**28 节设计时 task_id 来源必须重议**，两条候选：① 届时给 `Profile.Schedule` 补 `id` 字段（彼时改 record 的波及面由 28 节评估——含全仓 `new Profile(...)` 构造点与 AGENT.md frontmatter 是否加键）；② 换稳定派生规则（如 `profileName|cron|zone` 去 message，仍零改动但同 cron 同 zone 的多条 schedule 需再区分）。25 节不改（拍板 B 已定），本注记为 28 节的显式前置任务
- **跑通标准**：本节自身无独立 Demo，验收以 harness 全绿 + 真实到点触发一次为准；Demo 一（每日天气）自 25 节起具备钟推地基，31 节合并验收
