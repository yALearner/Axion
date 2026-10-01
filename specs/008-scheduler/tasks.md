# Tasks: 008-scheduler（定时任务钟推）

**Input**: Design documents from `specs/008-scheduler/`（plan.md / spec.md / research.md / data-model.md / contracts/ / quickstart.md）

**Prerequisites**: 需求文档 docs/requirements/008-scheduler.md（修订说明 ①~⑦ 已钉死口径——实施必须逐条对照，尤其 ⑦a~d「实施前优化」项与拍板 B）

## Format: `[ID] [P?] [Story] Description`（[P] 可并行；文件路径精确到包）

## Phase 1: Setup（前序现状核对）

**Purpose**: H0 依赖检查——确认前序交付物与需求文档「现状确认」一致（机械 grep，不得跳过）

- [X] T001 前序现状核对：grep 确认 `Profile.Schedule`（cron/zone/message 三字段嵌套 record，axion-core/src/main/java/com/axion/core/Profile.java）、`ProfileRegistry.list()`（注意方法名为 list 非 all——④ 适配）、`SessionManager.getOrCreate(String, String, String)`、`AgentService.process(Session, String)` 签名；确认 AgentScheduler 类不存在、全仓无 ThreadPoolTaskScheduler/CronTrigger 使用——与需求文档「现状确认（2026-09-07 实测）」逐项一致

## Phase 2: User Story 1 - 到点自动触发完整 ReAct 循环 (Priority: P1) ★ MVP

**Goal**: AgentScheduler 完整实现（registerAll 动态注册 + runOnce 锁/三元组/process/catch/finally）+ 四坑核心测试 + 两个最值钱回归先行

**Independent Test**: `AgentSchedulerTest` 四坑核心组 + 两个最值钱回归全绿

### Tests for User Story 1（先行，确保 FAIL 再实现）

- [X] T002 [US1] 创建 `AgentSchedulerTest` 于 axion-core/src/test/java/com/axion/core/AgentSchedulerTest.java，一次写全四坑核心组（课件 25 §四同款「实现之前写最划算」）：① 注册参数对——mock ThreadPoolTaskScheduler + ArgumentCaptor 抓 `schedule(Runnable, Trigger)`，断言 CronTrigger 带配置 cron 与时区（zone 非空 `TimeZone.getTimeZone(zone)` 等价；坑四/坑一）；② **最值钱之二（锁占跳过）**——先 `scheduler.lockFor(taskKey).lock()` 占锁 → runOnce → `verify(agentService, never()).process(...)`；③ **最值钱之一（异常不外抛 + 锁释放二进宫）**——`when(agentService.process(...)).thenThrow(RuntimeException)` → `assertDoesNotThrow(runOnce)` → **再跑一次** `verify(times(2))`（finally 漏 unlock 只有二进宫能抓住；坑三）；④ 会话三元组——`verify(sessionManager).getOrCreate("scheduler", "scheduler", profileName)` + 两次 runOnce 同一 Session。此时 AgentScheduler 不存在——编译失败即 red 证据

### Implementation for User Story 1

- [X] T003 [US1] 创建 `AgentScheduler` 于 axion-core/src/main/java/com/axion/core/AgentScheduler.java（需求文档「核心代码骨架」逐字对照 + ⑦ 项修订）：无组件注解纯类（G4-C1，②）；构造注入 taskScheduler/ProfileRegistry/SessionManager/AgentService 四依赖；`registerAll()` 扫 `profileRegistry.list()`（④ 适配）逐条 `taskScheduler.schedule(() -> runOnce(profile, sc), zone 空 ? new CronTrigger(cron) : new CronTrigger(cron, validatedZone(...)))`——**validatedZone 用 `ZoneId.of(zone)` 校验、非法抛带 profile/zone 的 IllegalStateException**（⑦b）；**schedule 返回值存 `scheduledTasks` Map**（⑦c）；`runOnce`——派生 key `profileName|cron|message`（拍板 B）computeIfAbsent ReentrantLock → tryLock 失败 log.info 跳过返回 → 三元组 `getOrCreate("scheduler","scheduler",profileName)` → `agentService.process(session, sc.message())` → catch Exception log.error(taskKey, e) 不外抛（不带 message 内容，NFR-3）→ finally unlock；package-private 测试钩子 `Lock lockFor(String taskKey)`（课件骨架同款）与 `int scheduledTaskCount()`（⑦c 断言用）

**Checkpoint**: T002 全部转绿（实现后），US1 独立可验证

---

## Phase 3: User Story 2 - 上一次没跑完，本次触发直接跳过 (Priority: P1)

**Goal**: 重叠防护行为钉死（锁占跳过 + 跳过日志文案）

**Independent Test**: `AgentSchedulerTest` 重叠跳过组全绿

### Tests for User Story 2

- [X] T004 [US2] `AgentSchedulerTest` 补重叠跳过细节（axion-core/src/test/java/com/axion/core/AgentSchedulerTest.java）：锁占跳过的**日志断言**——ListAppender（ContextLoaderTest 同款模式）捕获 runOnce 跳过分支的 log.info 含「跳过本次触发」与派生 taskKey；锁占跳过路径不产生任何 process 调用（verify never 已随 T002 覆盖，本任务补日志证据）。若暴露实现缺口随测即修

---

## Phase 4: User Story 3 - 任务失败，调度器不死 (Priority: P2)

**Goal**: 失败隔离行为钉死（异常不外抛 + 日志口径 NFR-3）

**Independent Test**: `AgentSchedulerTest` 失败隔离组全绿

### Tests for User Story 3

- [X] T005 [US3] `AgentSchedulerTest` 补失败日志口径（axion-core/src/test/java/com/axion/core/AgentSchedulerTest.java）：process 抛异常时 ListAppender 捕获 log.error——**断言含派生 taskKey 与异常、不含 message 内容**（NFR-3：用户可控值不进日志参数的机器证据）；异常路径后 `scheduledTaskCount()` 与锁状态不受影响（调度器不死）

---

## Phase 5: User Story 4 - 配置驱动注册：cron + 时区显式 + 非法配置不静默 (Priority: P2)

**Goal**: 注册行为钉死（⑦b 非法 zone 报错 / 全量注册 / ⑦c 句柄）

**Independent Test**: `AgentSchedulerTest` 注册组全绿

### Tests for User Story 4

- [X] T006 [US4] `AgentSchedulerTest` 补注册组（axion-core/src/test/java/com/axion/core/AgentSchedulerTest.java）：**⑦b 非法 zone**——Profile 带 `zone=Asia/Shangha`（拼错）→ registerAll 抛 IllegalStateException，message 含 profile 名与 zone；**多 Profile 多 schedules 全量注册**——schedule 调用次数 = schedules 总数；**⑦c 句柄**——registerAll 后 `scheduledTaskCount()` = schedules 总数；zone 空 → 单参 CronTrigger（系统时区，ArgumentCaptor 断言）

---

## Phase 6: User Story 5 - 装配：调度线程池多线程 + 注册时机 (Priority: P2)

**Goal**: 装配改造（ThreadPoolTaskScheduler setPoolSize(4) + AgentScheduler bean + registerAll）+ 装配断言

**Independent Test**: `CliAgentConfigurationTest` 增补全绿；`mvn test -pl axion-cli -am` 全绿

### Tests for User Story 5（先行，装配断言当前 red——bean 不存在）

- [X] T007 [US5] `CliAgentConfigurationTest` 增补（axion-cli/src/test/java/com/axion/cli/CliAgentConfigurationTest.java）：① AgentScheduler bean 存在且为 AgentScheduler 实例；② ThreadPoolTaskScheduler bean 的 `getScheduledThreadPoolExecutor().getCorePoolSize() > 1`（**⑦a 默认单线程跨任务阻塞的回归钉**）；③ 上下文启动无异常（registerAll 已在装配时调用——真实 taskScheduler 空 registry 零注册不炸）——当前 red，装配改造后转绿

### Implementation for User Story 5

- [X] T008 [US5] 装配改造：axion-cli/src/main/java/com/axion/cli/CliAgentConfiguration.java 新增 `ThreadPoolTaskScheduler` @Bean（`new ThreadPoolTaskScheduler()` + **`setPoolSize(4)`**（⑦a）+ `initialize()`；javadoc 注明容器关闭随 DisposableBean 自动 shutdown）+ `AgentScheduler` @Bean（构造注入 taskScheduler/ProfileRegistry/SessionManager/AgentService，方法体内**显式调 `registerAll()`**——替代 @PostConstruct，②；javadoc 注明钟推入口与 31 节 Demo 契约）

**Checkpoint**: 装配替换完成，cli 测试全绿

---

## Phase 7: Polish（收尾与全量门禁）

**Purpose**: 全量回归、收尾 DoD 证据、人工项清单

- [X] T009 全量门禁：`mvn clean verify` 全绿（**不写死用例数**——007 ⑦e 口径；静态门禁一并全绿）
- [X] T010 收尾 DoD 证据 + 人工项清单：① git diff 目检前序公共接口零改动——`Profile`/`Profile.Schedule`/`ProfileRegistry`/`SessionManager`/`AgentService` 逐字节未动（拍板 B 承诺）；② grep 核对 NFR-003：调度相关日志（info/error）只含派生 key 与异常、不含 message 内容；③ 对照 quickstart.md 输出机器判卷结论与**剩余人工项清单**（真实到点触发一次 / 配置驱动体感 / 端到端预演——人工项不自动执行，如实列待办）；④ 按 axion-spec gates.md 收尾 DoD 七项出具证据（宪法不变量 ⑥ 条含「无 Reactor/CompletableFuture/自建线程池」——ThreadPoolTaskScheduler 为陷阱表点名组件，grep 证实无其他自建池）

---

## Dependencies & Execution Order

### Phase Dependencies

- Setup（Phase 1）→ US1（Phase 2，含完整实现类）→ US2/US3/US4（Phase 3~5，测试钉行为）→ US5（Phase 6，装配）→ Polish（Phase 7）
- 无 Foundational 阶段——单类交付无共享基础设施（007 同款结构）

### User Story Dependencies

- **US1 (P1)**: 依赖 Setup——本节核心交付（实现类 + 四坑 + 两最值钱），MVP
- **US2/US3/US4 (P1/P2)**: 依赖 US1 T003——补行为钉（跳过日志/NFR-3/非法 zone/全量注册/句柄）
- **US5 (P2)**: 依赖 US1 T003——装配改造与断言

### Within Each User Story

- 测试先行（T002 含两个最值钱回归在实现前写——red 证据；T004~T007 补行为钉，暴露缺口随测即修）
- 红了当场修，不攒到最后（gates.md 任务级 DoD）

### Parallel Opportunities

- T002 唯一测试文件先行；T004/T005/T006 同文件分组建议顺序执行避免冲突
- T007 与 T003 可并行（不同模块不同文件）

## Implementation Strategy

### MVP First（US1 优先）

1. T001 现状核对 → T002 测试先行（red）→ T003 实现 → **STOP and VALIDATE**：`mvn test -pl axion-core -am` 全绿
2. US1 即完整调度器（注册 + 触发 + 防重叠 + 失败隔离）——可独立演示「到点自动跑 ReAct」

### Incremental Delivery

1. US1 → 钟推链路成立（四坑 + 两最值钱全钉）
2. US2 → 重叠防护日志证据；US3 → 失败日志口径（NFR-3）；US4 → ⑦b/⑦c 注册组
3. US5 → 装配改造收口（⑦a poolSize 回归钉）
4. Polish → 全量门禁 + DoD + 人工项

### Parallel Team Strategy

单人顺序执行即可（feature 规模小）；如多人：A 写 T002 测试、B 做 T008 装配改造（依赖 T003 完成）。

## Notes

- [P] tasks = 不同文件无依赖；[Story] label 映射 spec.md user story
- 每个 task 开始前：宪法 9 条速查（java-spring-init constitution-checklist.md）+ 停止清单预检（gates.md §停止清单）
- 交付清单白名单：AgentScheduler + CliAgentConfiguration 装配改造——**清单之外的对外概念一律先停**（停止清单第 1 条）
- 已定字面量保真：`Profile.Schedule` 三字段不动（拍板 B）、`ProfileRegistry.list()` 方法名不动（④）、会话三元组 `("scheduler","scheduler",name)`（停止清单第 2 条）
- 测试方法名英文（gates.md H1/H5）
- 不自动 commit / push / 运行 package.sh——同步时机由用户决定
