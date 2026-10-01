# Feature Specification: 定时任务子系统（状态持久化 + 管理端点 + 重启恢复 + 多 Agent 并存）

**Feature Branch**: `010-scheduler-mgmt`

**Created**: 2026-09-09

**Status**: Draft

**Input**: 需求文档 docs/requirements/010-scheduler-mgmt.md（课件第 28 节：全流程串联（二）让底座自己跑得稳；修订说明 ①~⑦ 口径全钉——拍板 A 补 id + 四维修正落位）

## Clarifications

### Session 2026-09-09

- Q: scheduled_tasks 的 next_run_at / last_run_at（及 task_executions 的 started_at）按什么时区口径存储和展示？ → A: 存 UTC（Instant + ISO-8601 TEXT，与审计表 created_at 同口径）；API 返回 UTC + 任务 zone 字段，管理台/展示层按任务 zone 转本地时间显示
- Q: POST /schedules/{id}/run 同步等待后返回什么内容？ → A: 返回 TaskExecutionView（success/started_at/duration_ms/error_message），与 GET /{id}/executions 历史条目同形状
- Q: 任务停用后，scheduled_tasks 的 next_run_at 怎么处理？ → A: 保留原值不动，列表同时显示状态列「已停用」；重新启用后 next_run_at 在下次真正执行时按 cron 更新（期间「下次触发」可能显示过期时间点，接受）

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 定时任务登记与持久化（Priority: P1）

skill 定义的定时任务（id + cron + zone + message）注册时登记进 `scheduled_tasks`（含算出的 next_run_at）；每次真正执行成功失败都写 `task_executions` 一条历史、并更新任务状态（last_run/last_status/run_count/next_run）——「跑过几次、上次成功没、下次几点」重启不丢（课件 §2.1③）。

**Why this priority**: 本节主线——25 节的内存注册 cron 升级为完整子系统的基础；状态与历史落 SQLite 是「重启不失忆」的前提。

**Independent Test**: `ScheduledTaskE2ETest`（mock provider、gate 内无 key）：启动即登记 → run_count=0、enabled=true；runNow 后 run_count=1、last_status=success、executions 一条成功记录。

**Acceptance Scenarios**:

1. **Given** 带 schedules（含 id）的 Agent 启动，**When** registerAll 执行，**Then** scheduled_tasks 有该任务（含算出的 next_run_at）、run_count=0、enabled=true
2. **Given** 任务真正执行成功，**When** 执行完成，**Then** task_executions 一条 success=true 记录（task_id/session_id/started_at/duration_ms）、任务状态更新（run_count+1、last_status=success、next_run 重算）
3. **Given** 任务真正执行失败，**When** 执行完成，**Then** task_executions 一条 success=false 记录（error_message 人可读）、任务状态更新（last_status=failure）——成败都记，宪法 V 同源
4. **Given** id 冲突的两个任务，**When** 注册，**Then** 启动报错且指明冲突的 Profile（⑦ P2）
5. **Given** frontmatter schedules 条目缺 id，**When** AgentLoader 解析，**Then** 启动报错（⑦c：不静默、不派生兜底）

---

### User Story 2 - 立即执行与停用（Priority: P1）

管理台「立即执行」（`runNow(taskId)`：手动触发、不等 cron、无视启用状态）与「停用」（enabled=false：到点直接跳过、不执行、不记历史）。⑦a：runNow 与到点触发共用同一把锁、同一执行体——并发不双跑（课件 §2.1③④）。

**Why this priority**: 运营管理面的核心操作；停用不记历史是最值钱回归；并发双跑是审计正确性的底线。

**Independent Test**: `AgentSchedulerTest` 增补：停用后到点不触发且不记历史（verify never + never recordExecution）；runNow 无视启用；⑦a 并发回归（runNow 与到点同时到达串行）。

**Acceptance Scenarios**:

1. **Given** 任务停用（enabled=false），**When** 到点触发，**Then** 直接跳过：不执行（verify process never）、不记历史（verify recordExecution never）
2. **Given** 任务停用，**When** 调用 runNow，**Then** 照常执行（无视启用状态）并记录历史
3. **Given** runNow 与到点触发同时到达，**When** 执行，**Then** 同一任务串行执行（不双跑——⑦a 同锁同入口）
4. **Given** 任务启用，**When** 到点触发，**Then** 正常执行并落库

---

### User Story 3 - 四端点管理（Priority: P2）

`ScheduleApiController` 四端点（统一 /api/v1 + 双信封）：GET /schedules（列表）、GET /schedules/{id}/executions（历史）、POST /schedules/{id}/run（立即执行——⑦b 同步等待 60s + 504）、PUT /schedules/{id}（启用/停用）；任务不存在 → 404（课件 §2.1④）。

**Why this priority**: 「能管理」的 API 面——管理台页与管理台之外的工具都消费它；双信封是 009 契约延续。

**Independent Test**: `ScheduleApiControllerTest`（standalone MockMvc，009 同款）：四端点契约 + 404 映射 + 双信封边界。

**Acceptance Scenarios**:

1. **Given** 有注册任务，**When** GET /schedules，**Then** 列表含任务/Profile/cron/下次触发/上次结果/次数/启用与否（ApiResponse 信封）
2. **Given** 某任务有执行历史，**When** GET /schedules/{id}/executions，**Then** 返回历史列表
3. **Given** 任务存在，**When** POST /schedules/{id}/run，**Then** 同步等待执行完成返回结果（超 60s → 504）
4. **Given** 任务存在，**When** PUT /schedules/{id} {enabled:false}，**Then** 停用生效（列表显示已停用）
5. **Given** 任务不存在，**When** 任意四端点按 id 访问，**Then** 404 + ErrorResponse 信封

---

### User Story 4 - 管理台「定时任务」页（Priority: P2）

管理台新增定时任务页：列表（任务/Profile/cron/下次触发/上次结果/次数/状态）+ 每行「立即执行」「启用·停用」——26 节只读管理台的**第一个写操作页**；复用 axion-admin-ui skill（⑦d：skill 只读纪律加例外条款）（课件 §2.1④）。

**Why this priority**: 「agent os 能执行智能定时任务」的落地界面；skill 纪律更新是 30 节复用的前置。

**Independent Test**: 人工项 /admin 定时任务页核对（列表渲染 + 写操作可用 + 其余页面仍只读）；skill 文件例外条款核对。

**Acceptance Scenarios**:

1. **Given** 服务运行且有注册任务，**When** 打开 /admin 定时任务页，**Then** 列表渲染任务与状态、每行有立即执行/启用停用按钮
2. **Given** 点击立即执行，**When** 等待返回，**Then** 结果显示执行结果（同步等待）
3. **Given** 点击停用，**When** 列表刷新，**Then** 状态显示已停用
4. **Given** axion-admin-ui skill，**When** 核对纪律条款，**Then** 含定时任务页例外（其余页面仍只读）

---

### User Story 5 - 重启恢复与多 Agent 并存（Priority: P2）

kill 再 serve：sessions/memory/schedules（状态与历史）/审计四样原样恢复；两个差异 Profile（A 文件工具、B HTTP 工具）同实例：工具/会话/定时三条隔离边界都守得住（课件 §2.2/§2.3）。

**Why this priority**: 「无状态实例」架构承诺的重启验证——哪一项没回来就说明有状态偷留在进程内存；多 Agent 是「OS」在核心阶段的最小体现。

**Independent Test**: `RestartRecoveryIT`（@Tag integration 手动跑）+ 多 Agent 隔离测试（mock 驱动 gate 内可跑部分）。

**Acceptance Scenarios**:

1. **Given** 跑对话+攒记忆+触发定时后 kill 进程，**When** 重新 serve，**Then** GET /sessions/{id} 完整历史、GET /memory 核心记忆、GET /schedules 状态与历史、llm_calls 跨重启不断档——四样全恢复
2. **Given** 两差异 Profile 并存，**When** 交替使用，**Then** 工具隔离（A 拿不到 B 独有工具）、会话隔离（各自会话不串）、定时隔离（A 定时异常 B 下个触发点照常）

---

### User Story 6 - Demo 前置环境（Priority: P2）

http.allowed_domains 加三样域名（api.open-meteo.com + webhook 域名 + 新闻源按需）、notify_channels 配好、测试 Profile 配 schedules（含 id + 显式时区）——31 节两个 Demo 的六项前置一次配齐（课件 §2.5）。

**Why this priority**: 现场调试最忌讳环境半好半坏；白名单会拦下自己的 Demo 是最容易自己绊倒自己的一条。

**Independent Test**: 人工项六项打勾清单。

**Acceptance Scenarios**:

1. **Given** application.yaml，**When** 核对白名单，**Then** http.allowed_domains 含天气源与 webhook 域名；file/shell 保持全拒
2. **Given** notify_channels，**When** 手动 notify 一次，**Then** 群收得到
3. **Given** 测试 Profile，**When** 核对 schedules，**Then** 含 id + cron + 显式时区（Asia/Shanghai）
4. **Given** OpenAiAutoConfiguration 排除，**When** serve 启动，**Then** 单 key 正常起（⑩d 已验证再核对）

---

### Edge Cases

- 停用任务到点触发：跳过不执行不记历史（最值钱回归）
- runNow 与到点触发并发：同锁同入口串行（⑦a）
- runNow 对停用任务：无视启用照常执行
- id 冲突：启动报错指明 Profile；id 缺失：启动报错（⑦c）
- 任务不存在：四端点 404
- run 端点同步等待超 60s：504（⑦b）
- 状态更新与历史写入非原子：单实例最终一致（接受，⑦ P2）
- task_executions 历史长期增长：清理归扩展

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: 两张表（schema.sql 手工增量）：`scheduled_tasks`（task_id 主键/profile_name/cron/zone/message/enabled/next_run_at/last_run_at/last_status/run_count——注册时写、每次触发更新）与 `task_executions`（id 主键/task_id/session_id/started_at/success/error_message/duration_ms——成功失败都记）；**时间字段（next_run_at/last_run_at/started_at）统一存 UTC**（Instant + ISO-8601 TEXT，与审计表 created_at 同口径），API 返回 UTC + 任务 zone 字段，管理台按任务 zone 转本地时间显示（Clarifications 2026-09-09）
- **FR-002**: `ScheduledTaskStore` 接口（**storage，落位拍板 2026-09-09**——Maven 依赖方向 storage 不得依赖 core，与 SessionRepository 同构：core 消费接口、JPA 实现与实体同模块封装）：register/recordExecution/isEnabled/setEnabled/list/executions + 值对象 `ScheduledTaskView`/`TaskExecutionView`（storage）；`JpaScheduledTaskStore` + 实体仓库（storage）
- **FR-003**: AgentScheduler 改造（008 交付物）：registerAll 登记（含 next_run_at）；执行入口拆「看启用状态 → 真正执行」（停用跳过不记历史）；成败都写 task_executions + 更新状态；runNow(taskId)（无视启用）；**⑦a：runNow 与 runOnce 共用同一把锁、同一 executeInternal**（并发不双跑）
- **FR-004**: `Profile.Schedule` 补 `id`（拍板 A）：frontmatter id 键、锁 key 与 task_id 直接用 id（008 派生 key 退役）、id 冲突启动报错（指明 Profile）、**⑦c：id 缺失启动报错**、CLAUDE.md 示例同步
- **FR-005**: ScheduleApiController 四端点（双信封）：GET /schedules、GET /schedules/{id}/executions、POST /schedules/{id}/run（**⑦b 同步等待 60s + 504**，返回体 data = TaskExecutionView，与 executions 历史条目同形状）、PUT /schedules/{id}（启用/停用：setEnabled 仅改 enabled 字段，next_run_at 保留原值不动——Clarifications 2026-09-09）；任务不存在 → 404
- **FR-006**: 管理台「定时任务」页（第一个写操作页：立即执行/启用停用）；**⑦d：axion-admin-ui skill 只读纪律加例外条款**
- **FR-007**: Demo 前置环境：三域名白名单（api.open-meteo.com + webhook + 新闻源按需）、notify_channels 配好、测试 Profile schedules（含 id + 显式时区）
- **NFR-001**: 全程同步阻塞（宪法 VII）；定时链路 = 人推链路换触发头、加推送尾——中间引擎完全复用
- **NFR-002**: 单任务失败不拖调度器（008 延续）；停用即不跑、不记历史
- **NFR-003**: 稳定性再验（⑥）：notify 超时（004 已有）、MCP 挂了不拖垮启动（005 已有）、error_message 可读、一次触发日志一条主线

### Key Entities

- **ScheduledTask**（storage 实体）：task_id/profile_name/cron/zone/message/enabled/next_run_at/last_run_at/last_status/run_count
- **TaskExecution**（storage 实体）：id/task_id/session_id/started_at/success/error_message/duration_ms
- **ScheduledTaskStore**（storage 接口，落位拍板 2026-09-09）+ ScheduledTaskView/TaskExecutionView（storage 值对象）
- **Profile.Schedule**（002 改造）：id + cron + zone + message 四字段
- **ScheduleApiController**（axion-web）：四端点 + DTO

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: ScheduledTaskE2ETest gate 内无 key 全绿——登记/立即执行/落库/停用全流程（课件原文五步）
- **SC-002**: 最值钱回归全绿：停用后到点不触发且不记历史（verify never ×2）
- **SC-003**: ⑦a 并发回归全绿：runNow 与到点同时到达同任务串行
- **SC-004**: 四端点契约全绿（双信封 + 404 + ⑦b 同步语义）
- **SC-005**: RestartRecoveryIT 重启四样恢复（手动跑）+ 多 Agent 三边界隔离
- **SC-006**: Demo 前置六项打勾
- **SC-007**: 全量 mvn clean verify 全绿（不写死用例数）

## Assumptions

- **前序交付物已实测就位**（2026-09-09）：AgentScheduler（008：registerAll/runOnce/lockFor/scheduledTasks）、schema.sql（003）、双信封与 standalone MockMvc 模式（009）、管理台前端 + skill（009）、notify 超时（004）、MCP 容错（005）——纯增量 + 5 处改造点
- **课件口径（用户拍板 2026-09-09）**：整体方案参考 28 节课件；Schedule 补 id 拍板 A
- **⑦ 四维修正**：a 同锁同入口 / b run 同步等待 / c id 缺失报错 / d skill 纪律更新 + P2 四项注记
- **改造点**（经拍板）：Profile.Schedule 补 id（002）、AgentLoader 解析（003）、AgentScheduler（008）、schema.sql（003）、管理台前端 + skill（009）、CLAUDE.md
- **明确不做**：运行时增删改 cron（29/30 节）、分布式协调、重试告警、misfire 补跑、task_executions 清理、状态/历史事务性（最终一致）
