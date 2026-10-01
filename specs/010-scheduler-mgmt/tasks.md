---
description: "Task list for 010-scheduler-mgmt 定时任务子系统（状态持久化 + 管理端点 + 重启恢复 + 多 Agent 并存）"
---

# Tasks: 010-scheduler-mgmt

**Input**: specs/010-scheduler-mgmt/ 的 plan.md（+ research/data-model/contracts/quickstart）、spec.md、docs/requirements/010-scheduler-mgmt.md

**Tests**: 本 spec 每个 US 都点名 Independent Test —— 测试任务一律 harness 先行（G3）。

**Organization**: 按 user story 分阶段，依赖顺序 US1 → US2 → US3 → US4 → US5 → US6。

**执行纪律（gates.md §过程纪律，每个 task 适用）**：写前 H3（第三方 API 本地依赖核实）/ 写中 H1·H5（只创建交付清单点名的对外概念、已定字面量逐字保真、异常不吞、测试方法名英文 + @DisplayName 中文）/ 写后（实现与测试一起落地、跑该模块测试、红了当场修、更新本文件勾选）。**不自动 commit / push / 运行 package.sh**——同步时机由用户决定。

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 可并行（不同文件、无未完成依赖）
- **[Story]**: 所属 user story（Setup / Foundational 无标签）

## Phase 1: Setup（H3 核实，实现前必做）

- [X] T001 [P] H3 核实 R4：反查本地 spring-context jar，确认 Spring 6.2 计算「某时刻后下一次 cron 触发」的公开 API 形态（`CronExpression.parse().next(Temporal)` 是否公开、zone 如何参与、`CronTrigger.nextExecution` 可见性）——结论记入 research.md R4；若公开 API 不足，按停止清单第 5/6 条停下报告（不得新增第三方依赖）
- [X] T002 [P] H3 核实 R10：确认本项目 Spring Boot 版本下 mock provider 的注入方式（`@MockitoBean` 可用性 vs 测试 @Configuration 替换 ProviderService bean），参照 axion-boot/src/test/java/com/axion/boot/WebSmokeIT.java 底座——结论记入 research.md R10
- [X] T003 [P] H3 核实 FR-7：确认 notify_channels 行的人工写入通道（004 无种子机制；sqlite3 CLI 或临时脚本），结论记入 research.md

## Phase 2: Foundational（所有 US 的前置，全部完成再进 US1）

- [X] T004 schema.sql 增量两表（scheduled_tasks 10 字段 / task_executions 7 字段，照 docs/requirements/010-scheduler-mgmt.md DDL 骨架逐字）：axion-storage/src/main/resources/schema.sql
- [X] T005 [P] ScheduledTask 实体（@Id String taskId，Instant 列用 InstantTextConverter，手写 getter + protected 无参构造，无 Lombok）：axion-storage/src/main/java/com/axion/storage/ScheduledTask.java
- [X] T006 [P] TaskExecution 实体（@Id @GeneratedValue(IDENTITY)，同 T005 形态）：axion-storage/src/main/java/com/axion/storage/TaskExecution.java
- [X] T007 [P] ScheduledTaskRepository（JpaRepository<ScheduledTask, String>）：axion-storage/src/main/java/com/axion/storage/ScheduledTaskRepository.java
- [X] T008 [P] TaskExecutionRepository（JpaRepository<TaskExecution, Long>，含 findByTaskIdOrderByIdDesc）：axion-storage/src/main/java/com/axion/storage/TaskExecutionRepository.java
- [X] T009 [P] Profile.Schedule 补 id（record 首位字段，拍板 A）+ 全仓 `new Profile.Schedule(...)` 构造点编译修复（含所有测试）：axion-core/src/main/java/com/axion/core/Profile.java
- [X] T010 ProfileLoader schedules 解析读 id + ⑦c：id 缺失启动报错（不静默、不派生兜底）：axion-core/src/main/java/com/axion/core/ProfileLoader.java
- [X] T011 [P] ScheduledTaskStore 接口六方法（register/recordExecution/isEnabled/setEnabled/list/executions）+ ScheduledTaskView / TaskExecutionView 两值对象（落位拍板 2026-09-09：与实现同落 storage）：axion-storage/src/main/java/com/axion/storage/ScheduledTaskStore.java 等 3 文件
- [X] T012 [P] ProfileLoaderTest 增补：schedules 带 id 解析成功 / id 缺失报错（⑦c 回归）：axion-core/src/test/java/com/axion/core/ProfileLoaderTest.java
- [X] T013 [P] ScheduledTaskRepositoryTest（直连 JDBC + PRAGMA table_info 核对两表列真实存在 + 存取 + task_id 主键唯一约束，NotifyChannelRepositoryTest 同款）：axion-storage/src/test/java/com/axion/storage/ScheduledTaskRepositoryTest.java
- [X] T014 JpaScheduledTaskStore 实现（依赖 T011）：axion-storage/src/main/java/com/axion/storage/JpaScheduledTaskStore.java
- [X] T015 [P] JpaScheduledTaskStoreTest（六方法行为 + 时间列 UTC 往返）：axion-storage/src/test/java/com/axion/storage/JpaScheduledTaskStoreTest.java
- [X] T016 [P] CLAUDE.md 核心数据模型 AGENT.md 示例 schedules 补 id（FR-4 改造点）：CLAUDE.md
- [X] 门禁：`mvn test -Dskip.npm` 全绿（storage 23 + core 67）

## Phase 3: US1（P1）定时任务登记与持久化

**独立测试**：ScheduledTaskE2ETest 骨架（mock provider、gate 内无 key）：启动即登记 → run_count=0、enabled=true；runNow 后 run_count=1、last_status=success、executions 一条成功记录。

- [X] T017 [US1] AgentSchedulerTest 增补 harness：registerAll 调用 store.register 且 nextRunAt 非空（T001 结论的 API 形态断言）；执行成功 → recordExecution(success=true) + 状态更新；执行失败 → recordExecution(success=false, error_message) + last_status=failure；id 冲突 → registerAll 启动报错且消息含冲突 Profile 名：axion-core/src/test/java/com/axion/core/AgentSchedulerTest.java
- [X] T018 [US1] AgentScheduler 改造第一批：构造器增 ScheduledTaskStore 参数；taskKey 退役改用 schedule.id（锁 key + scheduledTasks key）；registerAll 先查 id 冲突（冲突报错指明 Profile）再登记（store.register 含 next_run_at 计算）；新增 taskId→(Profile, Schedule) 注册映射：axion-core/src/main/java/com/axion/core/AgentScheduler.java
- [X] T019 [US1] 装配同步：JpaScheduledTaskStore 显式 @Bean + agentScheduler Bean 增 store 参数：axion-cli/src/main/java/com/axion/cli/CliAgentConfiguration.java
- [X] T020 [US1] ScheduledTaskE2ETest 骨架（@SpringBootTest + @AutoConfigureMockMvc + 临时 SQLite + T002 结论的 mock provider 注入 + 临时工作区带 schedules（含 id）的 mock Agent）：登记 + runNow 直调 scheduler bean + 落库断言（run_count/last_status/executions）：axion-boot/src/test/java/com/axion/boot/ScheduledTaskE2ETest.java
- [X] 门禁：`mvn test -Dskip.npm` 全绿（core 67 含 AgentSchedulerTest 20，008 存量不回归；E2E 骨架五步就位）

## Phase 4: US2（P1）立即执行与停用

**独立测试**：AgentSchedulerTest 增补：停用后到点不触发且不记历史（verify never ×2）；runNow 无视启用；⑦a 并发回归。

- [X] T021 [US2] AgentSchedulerTest 增补 harness（最值钱回归先行）：停用后 runOnce → process never + recordExecution never；runNow 对停用任务照常执行并记历史；⑦a 并发回归（S5 F1 口径：runNow 与到点同任务同时到达 → 串行不双跑——到点先占锁则 runNow 阻塞排队等锁后执行，008 虚拟线程锁模拟同款；runNow 先到则到点跳过）；enabled 检查在记历史之前；失败路径 error_message 人可读非堆栈（NFR-003）：axion-core/src/test/java/com/axion/core/AgentSchedulerTest.java
- [X] T022 [US2] AgentScheduler 改造第二批：runOnce 拆「store.isEnabled → 停用直接 return（不执行不记历史）→ executeInternal(waitForLock=false)」；executeInternal = 锁策略区分（S5 F1 修正：到点 tryLock 失败跳过、runNow lock() 阻塞排队——POST /run 必须返回执行记录）→ scheduler 三元组 session → process → 成败都 recordExecution（error_message 取最外层异常人可读 message 非堆栈，NFR-003）+ 状态更新（last_run/last_status/run_count/next_run 重算，T001 结论）→ finally unlock → 返回 TaskExecutionView；runNow(taskId) 查注册映射 → 无视 enabled → executeInternal(waitForLock=true) 同锁同入口（⑦a）；锁占跳过/失败日志口径与 008 存量测试对齐：axion-core/src/main/java/com/axion/core/AgentScheduler.java
- [X] 门禁：AgentSchedulerTest 20 用例全绿（存量 10 + 增补 10；最值钱回归 + ⑦a 并发 + ⑦c）

## Phase 5: US3（P2）四端点管理

**独立测试**：ScheduleApiControllerTest（standalone MockMvc，009 同款）：四端点契约 + 404 映射 + 双信封边界。

- [X] T023 [US3] ScheduleApiControllerTest harness 先行：GET /schedules 列表信封、GET /{id}/executions、POST /{id}/run 返回 TaskExecutionView + 超时 504、PUT /{id} enabled 切换（next_run_at 保留不动）、任务不存在 404、PUT 缺 enabled 400：axion-web/src/test/java/com/axion/web/api/ScheduleApiControllerTest.java
- [X] T024 [US3] ScheduleApiController 四端点 + DTO（统一 /api/v1/schedules 前缀、双信封；run 走 runWithTimeout 60s + AgentTimeoutException 复用 009；404 用 ResourceNotFoundException；仅 PUT enabled 字段、多余字段忽略）：axion-web/src/main/java/com/axion/web/api/ScheduleApiController.java
- [X] T025 [US3] ScheduledTaskE2ETest 补全五步（需求文档原文）：启动即登记 → GET /schedules 有任务 → POST /schedules/{id}/run 走真 ReAct（mock provider）→ 落库断言（run_count=1/last_status=success/executions 一条/GET /memory 查得到写入）→ PUT 停用 → 列表显示已停用 + 停用后到点不触发不记历史；gate 内无 key 全绿：axion-boot/src/test/java/com/axion/boot/ScheduledTaskE2ETest.java
- [X] 门禁：`mvn test -Dskip.npm` 全 reactor 绿（web 17 + boot 2 含 E2E 五步）

## Phase 6: US4（P2）管理台「定时任务」页

**独立测试**：人工项 /admin 定时任务页核对（列表渲染 + 写操作可用 + 其余页面仍只读）；skill 文件例外条款核对。

- [X] T026 [P] [US4] api.js 增 apiPost / apiPut（双信封同款解析，错误信封统一抛 message）：axion-web/src/main/frontend/src/api.js
- [X] T027 [US4] SchedulesView.vue（三态规范 + 列表七列 + 每行「立即执行」「启用·停用」两按钮 + 立即执行同步等待返回结果展示 success/duration/error + 停用后刷新显示已停用）：axion-web/src/main/frontend/src/views/SchedulesView.vue
- [X] T028 [US4] 路由与导航：main.js 增 /schedules 路由、App.vue navItems 增「定时任务」（token 照抄 skill，不加新样式值）：axion-web/src/main/frontend/src/main.js + axion-web/src/main/frontend/src/App.vue
- [X] T029 [US4] axion-admin-ui skill ⑦d 例外条款：只读纪律（第 4 条）加「定时任务页允许立即执行/启用停用两类写操作，其余页面仍只读」、第 5 条「只调 GET」加定时任务页例外、验收清单第 2 条「全站无任何写按钮」改「除定时任务页外」：.claude/skills/axion-admin-ui/SKILL.md
- [X] T030 [US4] 前端构建：`cd axion-web/src/main/frontend && npm ci && npm run build` 产物落 static/admin、/admin/schedules 子路由刷新不 404（SPA 回落复用 009）
- [X] 门禁：npm ci && npm run build 成功（产物落 static/admin，esbuild 进程占用需先清进程——本机已知坑）；人工核对留收尾

## Phase 7: US5（P2）重启恢复与多 Agent 并存

**独立测试**：RestartRecoveryIT（@Tag integration 手动跑）+ 多 Agent 隔离测试（mock 驱动 gate 内可跑部分）。

- [X] T031 [US5] RestartRecoveryIT（@Tag("integration") 真 key 手动跑；Assumptions.assumeTrue 无 key 自动跳过——ProviderSmokeIT 同款，F2 口径）：跑对话+攒记忆+触发定时后重启上下文 → GET /sessions/{id} 完整历史 / GET /memory 核心记忆 / GET /schedules 状态与历史 / llm_calls 跨重启不断档——四样全恢复：axion-boot/src/test/java/com/axion/boot/RestartRecoveryIT.java
- [X] T032 [US5] MultiAgentIsolationTest（@SpringBootTest + mock provider 同 E2E 底座，gate 内可跑）：两差异 Profile（A 只文件工具、B 只 HTTP 工具）三边界——工具隔离（A 会话 prompt 上下文不含 B 独有工具）/ 会话隔离（各自 session 不串）/ 定时隔离（A 定时异常后 B 下个触发点照常）：axion-boot/src/test/java/com/axion/boot/MultiAgentIsolationTest.java
- [X] 门禁：`mvn clean test -Dskip.npm` 全 reactor 绿（MultiAgent 3 + E2E 1；两个 IT 真 key 留人工）

## Phase 8: US6（P2）Demo 前置环境

**独立测试**：人工项六项打勾清单。

- [X] T033 [US6] application.yaml http.allowed_domains 增三域名（api.open-meteo.com + webhook 域名按实际渠道：飞书 *.feishu.cn / 企业微信 qyapi.weixin.qq.com + 新闻源域名按需注释）；file/shell 白名单保持现状：axion-boot/src/main/resources/application.yaml
- [X] T034 [US6] 测试 Profile schedules 配置（含 id + cron + 显式时区 Asia/Shanghai）落 .axion 工作区样例；notify_channels 行按 T003 结论人工写入；记录六项打勾执行步骤到 quickstart 第 7 节
- [X] T035 [US6] SchedulerFlowIT（@Tag("integration") 真 key；Assumptions.assumeTrue 无 key 自动跳过——F2 口径）：scheduler 会话复用（连续两次仍一条）/ llm_calls 恰 2 条 / tool_invocations 恰 2 条（http_get+notify）全成功 / webhook 真收到；失败路径（webhook 域名改白名单外 → Sandbox 拦、留 success=false 且 error_message 人可读、调度器不死）：axion-boot/src/test/java/com/axion/boot/SchedulerFlowIT.java
- [X] 门禁：application.yaml 三域名落位（file/shell 全拒）；六项打勾人工执行步骤记入 quickstart（人工核对留收尾）

## Phase 9: Polish & 收尾验证

- [X] T036 全量 `mvn clean verify` 全绿（含 P3C/SpotBugs/FindSecBugs/PMD 静态检查门禁）
- [X] T037 依赖方向核对：core 无 com.axion.storage import（grep 计数 0）；六条全局不变量自查（涉外 IO 过 Sandbox / 审计成败都落 / 无明文 key / session_id 只在 SessionManager 拼接 / 无异步模型 / 无 Spring AI 自动 tool 执行）
- [X] T038 收尾 DoD 七项证据 + 变更总结三段结构（改动点 / 重点 review 清单 / 如何验证）+ 剩余人工项清单（两个 IT 真 key、管理台页、Demo 六项、多 Agent 复验）

## Dependencies（user story 完成顺序）

```
Phase 1 Setup ──→ Phase 2 Foundational ──→ US1 ──→ US2 ──→ US3 ──→ US4 ──→ US5 ──→ US6 ──→ Phase 9
                                               （US4 前端可与 US5 并行；US6 的 T033/T034 可与 US5 并行）
```

- US1 依赖 Foundational（表/实体/接口/Profile 补 id 全部就位）
- US2 依赖 US1（executeInternal 在 T018 拆出、T022 收口）
- US3 依赖 US2（runNow 语义完整后四端点才有意义；E2E 五步在 T025 收口）
- US4 依赖 US3（前端调四端点）
- US5 依赖 US1~3（重启恢复与隔离验证需要完整子系统）
- US6 依赖 US2~3（Demo 前置用 runNow 触发验证）

## Parallel 机会

- Phase 1：T001 ∥ T002 ∥ T003（三个 H3 核实互不依赖）
- Phase 2：T005~T009、T012、T013、T016 多路并行（不同文件）；T014/T015 等 T011
- US4 与 US5 阶段级并行（前端 vs 后端测试，文件零交集）

## Implementation Strategy（MVP 先行）

1. **MVP = US1+US2**（P1）：两表 + store + AgentScheduler 登记/执行历史/runNow/停用——「跑过几次、上次成功没、下次几点」重启不丢 + 最值钱回归（停用不记历史）全绿，无 HTTP 面也能交付核心价值
2. **增量二 = US3+US4**：四端点 + 管理台页——运营方可查可管
3. **增量三 = US5+US6**：真 key 验证 + Demo 前置——31 节两个 Demo 的地基
4. 每个增量结束都跑对应模块测试门禁，不攒到最后

## 格式自检

- [ ] 全部 38 个 task 均含 `- [ ]` + T 编号 + 文件路径；US 阶段任务含 [US#] 标签；[P] 只标真实并行项
