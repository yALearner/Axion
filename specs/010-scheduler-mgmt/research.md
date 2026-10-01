# Research: 010-scheduler-mgmt 技术决策

> Phase 0 输出。所有 NEEDS CLARIFICATION 已通过代码现状核实解决（2026-09-09 实测）。H3 标注项 = 实现任务开始前须在本项目依赖里核实 API 形态（gates.md §过程纪律）。

## R1 接口与实现同落 storage（落位拍板 2026-09-09，停止清单 3 触发）

- **Decision**: `ScheduledTaskStore` 接口 + `ScheduledTaskView` / `TaskExecutionView` 两值对象 + `JpaScheduledTaskStore` 实现 + 两实体两仓库**全落 `axion-storage`**（`com.axion.storage` 单包 flat）；core 的 `AgentScheduler` 与 web 的 `ScheduleApiController` 直接消费该接口（与 `SessionRepository` 完全同构——core→storage 既有方向）。
- **Rationale**: 需求文档/技术方案原字面「契约在 core、实现在 storage」在 Maven 依赖方向上不可行——storage 不得依赖 core（core→storage 既有，反向即循环引用，整个多模块构建报错）；实测编译错误触发停止清单 3，用户拍板 A。依赖倒置语义不变：调用方依赖接口、JPA 实体封装在实现内不上浮。
- **Alternatives**: 接口留 core + 实现移入依赖 core+storage 的能力模块（scheduler 域无此模块，错误分层）；storage 加 core 依赖维持字面（Maven 循环引用，不可行）。

## R2 时间字段口径（Clarifications 2026-09-09 拍板）

- **Decision**: `next_run_at` / `last_run_at` / `started_at` 存 UTC `Instant`，落库经既有 `InstantTextConverter`（ISO-8601 TEXT，SQLite 无原生 TIMESTAMP）；API 返回 UTC + 任务 zone 字段，管理台展示层按任务 zone 转本地。
- **Rationale**: 与 `llm_calls` / `tool_invocations` / `sessions` 的 created_at 同口径（converter 已交付，storage 模块现成）；展示转换是前端职责（后端 API 保持时区中立）。
- **Alternatives**: 按 zone 存本地时间字符串（跨时区歧义、排序错乱）；固定上海时区展示（丢失任务自己 zone 的语义）。

## R3 AgentScheduler 内部改造结构

- **Decision**:
  - `taskLocks` key 与 `scheduledTasks` key、`task_id` 全部直接用 `Profile.Schedule.id`（008 派生 key `profileName|cron|message` 退役——FR-4 拍板 A）。
  - 新增 `taskRegistrations`：`taskId → Registration(Profile, Schedule)` 映射（需求文档「实现级明确」：008 的 `scheduledTasks` 只存 `ScheduledFuture`，runNow 需按 taskId 找回注册信息）。
  - 执行体拆两层：`runOnce(profile, sc)` = **先 `store.isEnabled(taskId)` → 停用直接 return（不执行、不记历史）** → `executeInternal(profile, sc, waitForLock=false)`；`runNow(taskId)` = 查注册信息 → `executeInternal(profile, sc, waitForLock=true)`（无视 enabled）。
  - `executeInternal` = 锁策略区分（⑦a 需求文档口径，S5 analyze F1 修正）：**到点触发 tryLock 失败跳过（008 坑二语义不变）**；**runNow lock() 阻塞排队**（POST /run 要返回执行记录，跳过则无记录可返回——与端点契约矛盾；60s 端点超时兜底，任务体后台跑完审计照常）→ 三元组 scheduler session → `agentService.process` → **成败都 `store.recordExecution`（error_message 取最外层异常人可读 message，非堆栈——NFR-003）** + 更新任务状态（last_run/last_status/run_count/next_run） → finally unlock；返回 `TaskExecutionView`（runNow 返回给端点，runOnce 丢弃）。
  - ⑦a 并发不双跑：runNow 与 runOnce 同一把锁（同 taskId）同一 executeInternal——两个入口天然串行（到点先到则 runNow 排队等锁；runNow 先到则到点跳过）。
- **Rationale**: 需求文档 FR-3 + ⑦a 原文；008 现有 runOnce 结构（tryLock/三元组/失败日志/finally unlock）原样内迁，行为回归由 008 存量测试兜底。
- **Alternatives**: runNow 独立执行路径（双跑风险，⑦a 拍板否定）；停用检查放 executeInternal 内（runNow 会被误拦）。

## R4 next_run_at 计算（H3）

- **Decision**: 注册时与每次真正执行后重算 `next_run_at` = 该 cron + zone 在基准时间之后的下一次匹配时刻（UTC 存储）。基准时间：注册时 = 当前时刻；执行后 = 本次执行完成时刻。
- **Rationale**: spec FR-001「注册时写、每次触发更新」；Clarifications 2026-09-09「停用保留原值不动、重新启用后下次真正执行时更新」——重算只在执行完成路径，setEnabled 不碰。
- **H3 核实结论（2026-09-09，T001）**: spring-context 6.2.19 实测 `CronExpression.parse(String)` 为 public static、`next(T extends Temporal & Comparable)` 为 public；`CronTrigger.nextExecution` 的字节码确认 zone 参与方式 = `expression.next(ZonedDateTime.ofInstant(latest, zoneId))`。故 next_run_at 计算 = `CronExpression.parse(cron).next(ZonedDateTime.ofInstant(baseline, zone)).toInstant()`；zone null/blank 按 `ZoneId.systemDefault()`（008 单参 CronTrigger 同口径）。零新增依赖。

## R5 run 端点 504 复用 AgentTimeoutException

- **Decision**: `POST /schedules/{id}/run` 超 60s 抛既有 `AgentTimeoutException`（009 已交付，`GlobalExceptionHandler` 已映射 504，`ErrorCode.GATEWAY_TIMEOUT` 已存在）。
- **Rationale**: 交付清单不含新异常类（停止清单 1：清单外对外概念须停）；定时执行本质仍是「Agent 调用」，语义贴合。
- **Alternatives**: 新建 ScheduleTimeoutException（清单外概念，否决）；改 009 异常类名（已定字面量，停止清单 2，否决）。

## R6 runWithTimeout 模式复用

- **Decision**: `ScheduleApiController` 自持 private `runWithTimeout`（`FutureTask` + `Thread.ofVirtual().start` + `task.get(60, SECONDS)`，Timeout→AgentTimeoutException / Interrupted→interrupt+IllegalState / Execution→按原类型重抛）——照抄 009 两份既有实现（AgentApiController / SessionApiController 各持一份的先例），不抽共享工具类。
- **Rationale**: 009 拍板「各 Controller 自持」形态；抽公共类 = 触碰前序交付物的新共享概念（停止清单 4）。
- **Alternatives**: 抽取公共 TimeoutRunner（改动前序模块公共接口，否决）。

## R7 前端与 skill 纪律（⑦d）

- **Decision**: 新增 `SchedulesView.vue`（列表 + 每行「立即执行」「启用·停用」）；`api.js` 增 `apiPost` / `apiPut`（同款双信封解析，`apiGet` 旁并列）；`main.js` 增 `/schedules` 路由、`App.vue` navItems 增「定时任务」；构建产物落 `static/admin/`（009 既有管线，frontend-maven-plugin）。
- `axion-admin-ui` SKILL.md ⑦d 更新：工程约定第 4 条「只读纪律」加例外条款——**定时任务页允许「立即执行」「启用·停用」两类写操作，其余页面仍只读**；第 5 条「只调 GET 端点」同步加「定时任务页例外（POST/PUT /api/v1/schedules）」；验收清单第 2 条「全站无任何写按钮」改为「除定时任务页外无任何写按钮」。
- **Rationale**: 需求文档 FR-6 + ⑦d 原文（课件点名第一个写操作页）；skill 更新是 30 节复用的前置。
- **Alternatives**: 不加例外条款直接写页面（skill 纪律与实际矛盾，30 节更乱）；skill 全文改写（最小改动原则，只动 3 处条款）。

## R8 实体与仓库形态

- **Decision**: `ScheduledTask` 实体 `@Id String taskId`（TEXT PK，无自增）；`TaskExecution` 实体 `@Id @GeneratedValue(IDENTITY) Long id`（AUTOINCREMENT，同 `ToolInvocation` 先例）；手写 getter + protected 无参构造（不引 Lombok，2026-09-05 拍板）；`ScheduledTaskRepository extends JpaRepository<ScheduledTask, String>`、`TaskExecutionRepository extends JpaRepository<TaskExecution, Long>`（按 task_id 查询用派生方法 `findByTaskIdOrderByIdDesc` 等）。
- **Rationale**: 与 storage 现有实体/仓库逐字同款；schema.sql 手工增量（`ddl-auto: none` + `spring.sql.init.mode: always` + 幂等 `CREATE TABLE IF NOT EXISTS`——既有管线，新表零接线改动）。
- **Alternatives**: Hibernate ddl-auto（坑八，否决）。

## R9 状态更新与历史写入非原子（spec 已接受）

- **Decision**: `recordExecution` 与任务状态更新为两次独立 Repository 写（各自事务）；中间崩溃可能历史有、状态没更新——单实例核心阶段最终一致，明确接受（需求文档「明确不做」）。
- **Rationale**: spec Edge Cases + 需求文档 ⑦ P2 注记；核心阶段不引事务协调。

## R10 测试布局与形态

- **Decision**:
  - `ScheduledTaskE2ETest`（@SpringBootTest + @AutoConfigureMockMvc + 临时 SQLite + mock Provider）落 **axion-boot**——`WebSmokeIT` 同款底座（`DynamicPropertySource` 覆盖 provider 与数据源；静态块建 `.axion` 父目录；@Tag("integration") 与否按 gate 需要——**gate 内无 key 全绿 = 必须进默认 surefire 跑，故不打 integration tag，用 mock provider**）。
  - `SchedulerFlowIT` / `RestartRecoveryIT`（真 key）落 axion-boot，@Tag("integration") 手动跑（既有 `ProviderSmokeIT` / `WebSmokeIT` 先例）。
  - `AgentSchedulerTest` 增补在 axion-core（008 测试类原位增补）；`ScheduleApiControllerTest`（standalone MockMvc）在 axion-web（009 同款）；`ScheduledTaskRepositoryTest` 在 axion-storage（NotifyChannelRepositoryTest 同款直连 JDBC + PRAGMA 核对列）。
- **Rationale**: 各模块测试随模块走（009 先例）；gate 无 key 约束决定 E2E 用 mock provider 进默认测试。
- **H3 核实结论（2026-09-09，T002）**: Spring Boot 3.5.16 + spring-test 6.2.19 实测 `org.springframework.test.context.bean.override.mockito.MockitoBean` 存在——E2E 用 `@MockitoBean ProviderService` 替换真实 Provider bean（stub 返回固定 ChatResponse 走完 ReAct），注入点全量收 mock。

## R11 Demo 前置配置落位

- **Decision**: `application.yaml` `http.allowed_domains` 增 `api.open-meteo.com` + webhook 域名（飞书 `*.feishu.cn` / 企业微信 `qyapi.weixin.qq.com`，按实际渠道注释选一）+ 新闻源域名按需注释；file/shell 白名单保持现状（Demo 不用）；notify_channels 行由人工 INSERT（004 无自动种子机制，六项打勾人工项）；测试 Profile（.axion/agents/ 下）配 schedules（含 id + 显式 zone）。
- **Rationale**: FR-7 + spec US6 六项人工清单；白名单会拦自己的 Demo（需求文档原话）。
- **H3 核实结论（2026-09-09，T003）**: 本机无 sqlite3 CLI，但 Python（D:\python）自带 sqlite3 模块——notify_channels 行人工 INSERT 通道 = `python -c "import sqlite3; ..."` 直连 `.axion/axion.db`（记录到 quickstart 第 7 节）。
- **Alternatives**: 白名单加 `*`（泄沙箱防线，否决）。

## R12 技术方案 §9.2 不一致注记

- **事实**: 技术方案 §9.2 `scheduled_tasks` 实体字段表多一行 `updated_at`，而 §8.5 正文、课件、需求文档 DDL 骨架均为 10 字段无 `updated_at`。
- **Decision**: 以需求文档 DDL 骨架为准（10 字段），§9.2 的 `updated_at` 行不落地；收尾时按宪法 Governance「冲突文档同步修正」向用户报告，由用户拍板是否回写技术方案。
- **Rationale**: spec FR-001 是本节契约；多数源（§8.5 + 课件 + 需求文档）一致。

## R13 已核实的前序依赖现状（2026-09-09 实测）

- schema.sql 无两表 ✓；`ScheduledTaskStore` 不存在 ✓；`Profile.Schedule` = cron/zone/message 三字段无 id ✓；存量 agent 无 schedules（补 id 零迁移）✓
- `AgentScheduler` 现有：taskKey 派生、`scheduledTasks` 存 ScheduledFuture、runOnce 一体式（锁/会话/process/日志）✓
- `ErrorCode.GATEWAY_TIMEOUT(504)` + `AgentTimeoutException` + GlobalExceptionHandler 映射已交付 ✓
- `InstantTextConverter` 已交付 ✓；schema.sql 自动执行管线已接线（`spring.sql.init.mode: always`）✓
- 前端：Vue3+Vite、base '/admin/'、api.js 只有 apiGet、五页只读、navItems 在 App.vue ✓
- `axion-admin-ui` SKILL.md 只读纪律在第 4/5 条、验收清单第 2 条「全站无任何写按钮」✓
