# Feature Specification: 定时任务钟推（AgentScheduler 第三种触发源）

**Feature Branch**: `008-scheduler`

**Created**: 2026-09-07

**Status**: Draft

**Input**: 需求文档 docs/requirements/008-scheduler.md（课件第 25 节：定时任务模块；新版 PDF 已 PyMuPDF 提取，修订说明 ①~⑦ 口径全钉）

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 到点自动触发完整 ReAct 循环（Priority: P1）

Agent 的 `AGENT.md` frontmatter 配了 `schedules`（cron + zone + message），进程启动注册后到点自动拼消息调 `AgentService.process`，跑完完整 ReAct 循环，`llm_calls`/`tool_invocations` 照常落账——跟人推的一次对话完全一样记账，不为钟推新设任何审计逻辑（需求文档场景一，本节验收场景）。

**Why this priority**: 本节存在的意义——补上"Agent 不用人喂话、自己到点干活"的基础能力缺口（课件 §一）；两个验收 Demo（31 节每日天气/科技日报）的钟推地基（需求文档 §13 硬条件）。

**Independent Test**: `AgentSchedulerTest` 全 mock 验证 runOnce 调用链（会话三元组 + process 调用 + 注册参数）；人工部分真实到点触发一次（每分钟 cron 真等一次）。

**Acceptance Scenarios**:

1. **Given** Profile 带 schedules 配置且进程已启动注册，**When** 到点触发，**Then** 以 `("scheduler","scheduler",profileName)` 三元组取 Session 后调 `AgentService.process(session, message)`——与 CLI/Web 完全同一入口
2. **Given** 定时触发的一次对话，**When** 执行完成，**Then** `llm_calls`/`tool_invocations` 照常落账（审计零新增——AgentService 内部既有路径）
3. **Given** 同一 Profile 的两次定时触发，**When** 各自执行，**Then** 拿到同一 Session 实例（会话历史自然累积，靠 max_history_turns 截断兜底）

---

### User Story 2 - 上一次没跑完，本次触发直接跳过（Priority: P1）

ReAct 循环跑得比调度间隔还长（每分钟触发但上一次未结束）：同一任务拿不到本地锁，本次触发跳过、不排队、不并行跑两份（坑二，需求文档场景二）。

**Why this priority**: 重叠执行会让同一 Agent 并发跑两份 ReAct——审计混乱、资源翻倍；课件 §四最值钱回归之二（锁必须放掉的"二进宫"断言）。

**Independent Test**: 先占住任务的锁再调 runOnce → `verify(agentService, never()).process(...)` + 跳过日志断言。

**Acceptance Scenarios**:

1. **Given** 任务上一次触发仍持有锁，**When** 本次触发执行 runOnce，**Then** 直接跳过（不调 process、不排队），log 记「跳过本次触发」
2. **Given** 任务执行完成（含异常路径），**When** runOnce 返回，**Then** 锁必在 finally 中释放——「二进宫」断言：第二次 runOnce 能再次进入 process（times(2)）

---

### User Story 3 - 任务失败，调度器不死（Priority: P2）

某次定时任务内部抛异常：只记日志（含任务派生 key，不带 message 内容）、异常不外抛、其他任务的下一次触发不受影响；失败的那次调用依然走 AgentService 内部完整审计（坑三，需求文档场景三）。

**Why this priority**: 调度器是常驻基础设施——一次任务失败带崩整个调度线程池等于所有 Agent 的钟推全停；课件 §四最值钱回归之一。

**Independent Test**: mock `agentService.process` 抛 RuntimeException → `assertDoesNotThrow(runOnce)` + 再跑一次 `verify(times(2))`（锁释放证据）+ 日志含任务 key。

**Acceptance Scenarios**:

1. **Given** process 内部抛异常，**When** runOnce 执行，**Then** 异常不外抛（调度器不死）、log.error 记任务 key + 异常栈
2. **Given** 一次任务失败后，**When** 同一任务下一次到点触发，**Then** 正常执行（锁未被卡死——二进宫断言）
3. **Given** 任务失败，**When** 检查审计，**Then** 该次调用的 llm_calls/tool_invocations 照常落账（失败与成功同路径，无钟推专属审计）

---

### User Story 4 - 配置驱动注册：cron + 时区显式 + 非法配置不静默（Priority: P2）

schedules 来自 AGENT.md frontmatter（改 cron 不重新编译），注册时 CronTrigger 带上配置的 cron 与 zone（坑四：服务器时区不替用户做主）；zone 非法（拼错）→ 启动明确报错，不静默回退 GMT（⑦b，001 ConfigLoader 纪律）；schedule 返回句柄存入 Map（⑦c 预留 28 节启停/重调度）。

**Why this priority**: 配置即 Agent 原则的延伸（坑一）；「到点不触发」是陷阱表点名的故障形态——zone 静默回退 GMT 正是它的时区变体；句柄预留是 28 节零重构的承诺。

**Independent Test**: mock ThreadPoolTaskScheduler + ArgumentCaptor 抓注册参数（cron + zone）；非法 zone 注册抛 IllegalStateException；多 Profile 多 schedules 全量注册。

**Acceptance Scenarios**:

1. **Given** Profile 的 schedules 含 cron 与 zone，**When** registerAll 执行，**Then** schedule 注册参数为 `CronTrigger(cron, TimeZone(zone))`——时区显式传递
2. **Given** zone 为空，**When** 注册，**Then** 单参 `CronTrigger(cron)` 按系统时区（Profile javadoc 口径）
3. **Given** zone 为拼错值（如 Asia/Shangha），**When** registerAll 执行，**Then** 抛 IllegalStateException，message 含 profile 名与 zone（不静默回退 GMT）
4. **Given** 多个 Profile 多条 schedules，**When** registerAll 执行，**Then** schedule 调用次数 = schedules 总数；每次返回句柄存入 scheduledTasks Map

---

### User Story 5 - 装配：调度线程池多线程 + 注册时机（Priority: P2）

`CliAgentConfiguration` 装配 ThreadPoolTaskScheduler（`setPoolSize(4)`——默认单线程下长 ReAct 会跨任务互相阻塞，⑦a）与 AgentScheduler，装配时显式调 registerAll（替代 @PostConstruct）；容器关闭自动 shutdown。

**Why this priority**: 跨任务互斥是四文档之外的盲点（防重叠锁只管同任务）；装配是 003 改造点，CliAgentConfigurationTest 增补承载。

**Independent Test**: `CliAgentConfigurationTest` 增补：AgentScheduler bean 存在；ThreadPoolTaskScheduler bean 的 `getScheduledThreadPoolExecutor().getCorePoolSize() > 1`（⑦a 回归钉）；上下文启动无异常（空 registry 零注册不炸）。

**Acceptance Scenarios**:

1. **Given** 应用启动，**When** 检查 Spring 上下文，**Then** AgentScheduler bean 存在且注册已完成（registerAll 已在装配时调用）
2. **Given** 应用启动，**When** 检查调度线程池，**Then** 核心线程数 > 1（默认单线程跨任务阻塞的回归钉，⑦a）
3. **Given** 容器关闭，**When** shutdown 执行，**Then** 调度线程池随 DisposableBean 语义自动关闭

---

### Edge Cases

- 锁被占：本次触发跳过不排队（tryLock 语义）
- 任务异常：不外抛、锁 finally 释放（二进宫断言）、日志带派生 key 不带 message 内容（NFR-3）
- zone 空：按系统时区；zone 非法：启动报错（⑦b）
- 无 schedules 的 Profile：零注册不炸
- 进程宕机错过的触发点：不补跑（Spring 默认语义，明确不做已列）
- 容器关闭时进行中的任务：interrupt 打断、审计记失败（如实注记，不做优雅停机）

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: `AgentScheduler`（axion-core，无组件注解纯类）——`registerAll()` 扫 `ProfileRegistry.list()` 每个 Profile 的 `schedules` 逐条 `taskScheduler.schedule(runOnce, new CronTrigger(cron, zone))` 动态注册（不用静态 @Scheduled——坑一配置驱动）；zone 空 → 单参 CronTrigger 按系统时区；zone 非空先经 `ZoneId.of` 校验，非法 → 包装带 profile/zone 的 IllegalStateException（⑦b 不静默回退 GMT）；`schedule` 返回值存 `Map<String, ScheduledFuture<?>>`（⑦c 预留）
- **FR-002**: `runOnce` 重叠防护（坑二）——按派生 key `profileName|cron|message`（拍板 B）每任务一把 `ReentrantLock`，`tryLock` 失败 → log 跳过返回；`finally` unlock（最值钱之二）
- **FR-003**: 失败隔离（坑三）——`runOnce` 内 `catch (Exception)` → `log.error`（任务 key + 异常栈，不带 message 内容）→ 不外抛；审计零新增（AgentService 内部既有路径落账）
- **FR-004**: 会话身份（宪法 VIII）——`sessionManager.getOrCreate("scheduler", "scheduler", profileName)`，历次触发复用同一 Session
- **FR-005**: 装配（003 改造点）——`CliAgentConfiguration` 新增 ThreadPoolTaskScheduler @Bean（`setPoolSize(4)` ⑦a + `initialize()`，容器关闭自动 shutdown）+ AgentScheduler @Bean（构造注入四依赖）+ 装配时显式调 registerAll（替代 @PostConstruct）
- **NFR-001**: 任务体全程同步阻塞调 `AgentService.process`，不引入 Reactor/CompletableFuture；调度线程池由 Spring ThreadPoolTaskScheduler 承担（宪法 VII + 陷阱表点名组件，不属于自建线程池禁条）
- **NFR-002**: 单任务失败不得拖垮调度器、不得影响其他任务触发（catch 边界 = runOnce 全方法）
- **NFR-003**: 日志参数只带任务派生 key 与异常，不拼接任何**运行时用户可控值**（CRLF 口径只管运行时输入）；派生 key 含 message 属 frontmatter 运营配置（Agent 作者可控、非运行时输入），随 key 进日志可接受（修订说明 ⑧a 澄清）

### Key Entities

- **AgentScheduler**（本节新增）：钟推入口——持有 taskScheduler/ProfileRegistry/SessionManager/AgentService + taskLocks（per-task ReentrantLock）+ scheduledTasks（ScheduledFuture 句柄）
- **Profile.Schedule**（002 已交付，不改）：`cron`/`zone`/`message` 三字段嵌套 record——schedules 的定义源
- **派生锁 key**（本节约定，拍板 B）：`profileName|cron|message` 字符串——进程内锁与日志标识；28 节 task_id 来源必须重议（⑦d 前置注记）
- **Session 三元组**（宪法 VIII 约定）：`("scheduler","scheduler",profileName)`——钟推会话身份

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 到点自动触发完整 ReAct 循环且审计照常落账——钟推与人推走同一 AgentService 链路（宪法 VIII 机器证据：verify process 调用 + 三元组断言）
- **SC-002**: 四坑全被 harness 钉死——注册参数对（坑四/坑一）、锁占跳过（坑二）、异常不外抛 + 锁释放二进宫（坑三）、会话三元组同一 Session
- **SC-003**: 两个最值钱回归（catch 不崩调度器 / finally 放锁二进宫）在实现之前写并全绿
- **SC-004**: 装配回归钉——调度线程池 corePoolSize > 1（⑦a）；zone 非法启动报错（⑦b）；句柄 Map 有值（⑦c）
- **SC-005**: 全量 `mvn clean verify` 全绿（不写死用例数，007 ⑦e 口径）

## Assumptions

- **前序交付物已实测就位**（2026-09-07）：`Profile.Schedule`（cron/zone/message）、`ProfileRegistry.list()`（课件骨架 all() 的现状对应）、`SessionManager.getOrCreate`、`AgentService.process(Session, String)` 全部就位；AgentScheduler 空壳待建——纯增量
- **课件口径（用户拍板 2026-09-07）**：25 节新版 PDF 已 PyMuPDF 提取；修订说明 ②③④ 三处形态适配经简报确认 + 拍板 B（锁 key 派生不改 Schedule）
- **线程模型口径**：ThreadPoolTaskScheduler 是 CLAUDE.md 陷阱表点名组件；任务体同步阻塞（宪法 VII）；池大小 4（⑦a）
- **跨节契约**：31 节两个定时 Demo 消费本节的 schedules 触发源（AGENT.md frontmatter 三键，不改本节代码）；28 节补两张表/管理端点时 task_id 来源必须重议（⑦d：message 进派生 key 会执行历史断链——候选：届时给 Schedule 补 id 或换稳定派生规则）；Demo 的 HTTP 域名需在 007 白名单内（wttr.in 已就位）
- **明确不做**（文档列明）：28 节两张表/四端点、运行时增删改 cron、分布式协调、失败重试/告警、misfire 补跑、优雅停机、日志节流
