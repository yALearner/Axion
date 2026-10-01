# 010-scheduler-mgmt 代码 Review 指南

> 生成：2026-09-10（交付后复盘 + 全量门禁实跑 + 管理台演示闭环后）。
> 复盘全记录见 `specs/010-scheduler-mgmt/flow-status.md`（含落位拍板 A、S5 analyze F1 锁语义修正、遗留待办四条、停止清单触发记录），本指南是 review 导航。

## 一、全景：定义在文件 → 登记进表 → 钟推/手推同锁 → 成败都落账 → 四端点可查可管

```
AGENT.md frontmatter schedules（id + cron + zone + message，⑦d 拍板 A 补 id）
  → ProfileLoader 解析（⑦c：id 缺失启动报错，不静默不派生兜底）
  → AgentScheduler.registerAll：id 冲突报错指明 Profile（⑦ P2）→ CronTrigger 校验（007 ⑦b 口径）
    → store.register 登记 scheduled_tasks（含算出的 next_run_at，H3 T001 CronExpression API 实测）
    → taskScheduler.schedule 动态注册（坑一）+ 句柄/注册信息双 Map
  → 两种触发：
      钟推 runOnce：store.isEnabled 停用直接 return（不执行不记历史——最值钱回归）
         → executeInternal(waitForLock=false)：tryLock 失败跳过（008 坑二）
      手推 runNow（管理台/API）：查注册映射 → executeInternal(waitForLock=true)：lock() 排队
         （S5 analyze F1 修正：POST /run 必须返回执行记录，跳过则无记录可返回；60s 端点超时兜底）
  → executeInternal：scheduler 三元组 Session（宪法 VIII）→ agentService.process（与人推同一引擎）
     → 成败都 store.recordExecution（task_executions + last_run/last_status/run_count/next_run 迁移）
     → error_message 人可读非堆栈（NFR-003）
  → ScheduleApiController 四端点（双信封 + 404/400/504）→ 管理台定时任务页（第一个写操作页，⑦d skill 例外）
  → 重启：registerAll 重注册只覆盖定义列，状态列保留（JpaScheduledTaskStore 重注册语义，US5）
```

落位拍板（停止清单 3 触发）：接口与实现同落 storage（Maven 依赖方向 storage 不得依赖 core，SessionRepository 同构），spec FR-002/需求文档 ⑧/技术方案 §8.5 已同步修正。

## 二、逐文件梳理

### axion-storage/com/axion/storage（8 新 + schema.sql 增量）

| 文件 | 关键点 |
|------|--------|
| `ScheduledTaskStore` | 六方法契约（register/recordExecution/isEnabled/setEnabled/list/executions）；javadoc 钉死三语义：重注册保状态列 / recordExecution=历史+状态迁移 / setEnabled 只动 enabled（Clarifications 2026-09-09） |
| `ScheduledTaskView` / `TaskExecutionView` | 契约值对象（JPA 实体不上浮）；时间统一 UTC Instant；run 端点返回体与 executions 条目同形状 |
| `ScheduledTask` | 实体无通用 setter（2026-09-05 拍板）：领域状态迁移方法 mergeDefinition / applyExecutionResult / applyEnabled 收口修改路径 |
| `TaskExecution` | IDENTITY 自增（AUTOINCREMENT 对齐）；success/error_message 成败都记（宪法 V 同源） |
| `JpaScheduledTaskStore` | **重注册保留状态列**（register 找已有行 mergeDefinition——重启不失忆的前提）；recordExecution 内部 next_run 重算（CronExpression **双 @Nullable 判空**，SpotBugs NP 门禁）；list 按 taskId 升序（F3）、executions 最新在前 |
| `schema.sql` | 两表增量（DDL 骨架逐字，BOOLEAN 列 SQLite affinity 可存 0/1）；`CREATE TABLE IF NOT EXISTS` 幂等走既有 spring.sql.init 管线 |

### axion-core/com/axion/core（3 改）

| 文件 | 关键点 |
|------|--------|
| `Profile.Schedule` | +id 首位字段（拍板 A）：锁 key 与 task_id 直接用 id，008 派生 key 退役（改 message 换任务身份的断链风险解除） |
| `ProfileLoader` | schedules 解析读 id；⑦c：缺 id 抛 IllegalArgumentException（消息含 Agent 名——与 007 zone 校验同款不静默纪律） |
| `AgentScheduler` | 构造器 +store；registerAll 冲突检测（owners Map）+ 登记 + 注册信息映射 `taskId→(Profile,Schedule)`；runOnce 拆启用检查；executeInternal 锁策略区分（waitForLock 参数：tryLock 跳过 vs lock() 排队——F1 修正）；成败都 recordExecution；runNow 未注册明确报错 |

### axion-web（1 新 + 前端 4 文件）

| 文件 | 关键点 |
|------|--------|
| `ScheduleApiController` | 四端点双信封；requireTask 以 store.list 为存在性真相源 → 404；PUT 缺 enabled → 400；run 走 runWithTimeout（FutureTask + virtual thread 60s，复用 009 AgentTimeoutException→504）；EI_EXPOSE_REP2 抑制（注入单例先例） |
| `api.js` | +apiPost/apiPut（双信封同款解析——写操作封装与 apiGet 并列） |
| `SchedulesView.vue` | 列表七列 + 立即执行（同步等待 loading 态）/启用停用；时间按任务 zone 转本地（Intl.DateTimeFormat，Clarifications 口径）；**停用行提示**「停用仅停止到点自动触发」+ **「上次完成」文案**（任务级/工具级成败语义，2026-09-10 拍板） |
| `main.js` / `App.vue` | /schedules 路由 + 导航项（token 照抄 skill） |

### 装配与配置（2 改）

| 文件 | 关键点 |
|------|--------|
| `CliAgentConfiguration` | JpaScheduledTaskStore 显式 @Bean + agentScheduler 增 store 参数（形态机械适配 ② 延续） |
| `application.yaml` | http 白名单**加**三域名（保留 007 原条目——存量 weather Agent 写死 wttr.in）；file/shell 保持 007 原状 |

### 测试（7 新 + 4 增补）

| 文件 | 关键点 |
|------|--------|
| `ScheduledTaskE2ETest`（boot） | 课件五步 gate 内无 key 全绿：@SpringBootTest 真上下文 + @MockitoBean ProviderService（T002 H3 实测 spring-test 6.2.19 可用）+ 两段式 mock（save_memory 工具调用→最终答复，AssistantMessage 四参构造 protected 匿名子类走通）；类专属 db 文件 + agents 目录整体重建（测试类互不踩） |
| `MultiAgentIsolationTest`（boot） | 三边界：工具隔离（Prompt options ToolCallbacks 名断言，H3：ChatOptions 不暴露 getToolCallbacks 需 instanceof 收窄）/会话隔离（测试专属 user）/定时隔离（mock 按 profile 分派异常） |
| `SchedulerFlowIT` / `RestartRecoveryIT`（boot，@Tag integration 真 key） | 链路对账（llm_calls 恰 2/触发、tool_invocations 恰 2、会话复用）+ 失败路径（白名单外 → Sandbox 拦 success=false + 调度器不死）+ 重启四样恢复（第二上下文 web(NONE) 同 db 验证）；assumeTrue 无 key 跳过（ProviderSmokeIT 同款 F2 口径） |
| `ScheduleApiControllerTest`（web） | standalone MockMvc 六用例；显式 JavaTimeModule + WRITE_DATES_AS_TIMESTAMPS=false（Instant 序列化 ISO，对齐 Boot 默认） |
| `ScheduledTaskRepositoryTest` / `JpaScheduledTaskStoreTest`（storage） | PRAGMA 核对两表列逐字存在（坑八同款）；重注册保状态列 / 成败迁移 / setEnabled 只动 enabled / @Nullable 双判空路径 |
| `AgentSchedulerTest`（core，008 存量 10 + 增补 10） | 最值钱回归（停用 verify never ×2）/ ⑦a 并发两方向（runNow 排队等锁 + 到点 tryLock 跳过）/ ⑦c / id 冲突指明 Profile / error_message 非堆栈 |

## 三、重点 review 清单（按风险排序）

1. **重注册保状态列**（`JpaScheduledTaskStore.register`，storage/JpaScheduledTaskStore.java:39）：重启时 registerAll 再登记不能覆盖 enabled/last_*/run_count——「重启不失忆」的前提；restartRecovery 的 run_count 断言与此互为证据
2. **executeInternal 锁策略区分**（`AgentScheduler.java:144`）：waitForLock=true 走 lock() 排队（runNow 必须返回记录）、false 走 tryLock 跳过（到点语义不变）；两个 ⑦a 并发回归测试钉死两方向
3. **成败都落账**（executeInternal → recordExecution）：process 异常被消化后仍记 success=false + 人可读 error_message；任务级 success 语义 = 循环完整跑完（管理台文案已改「上次完成」避免运营误解——遗留待办 1 的处置）
4. **next_run 计算**（两处：scheduler 注册时 + store 执行后）：CronExpression parse/next 双 @Nullable 判空（SpotBugs NP 门禁实测触发过）；zone 空按系统时区（008 口径）
5. **停用语义边界**（runOnce 第一行）：enabled 检查在锁与记历史之前——停用不执行不记历史（最值钱回归）；runNow 无视启用（US2-2 拍板，管理台有提示文案）
6. **task_id 全局唯一**（registerAll owners Map）：跨 Profile 冲突报错指明两个 Profile 名（⑦ P2）；数据库 task_id 主键是第二道防线
7. **run 端点同步语义**（ScheduleApiController.runWithTimeout）：60s + 504 复用 AgentTimeoutException；超时后任务体继续后台跑完、审计照常（009 ⑨c 口径）
8. **测试工作区隔离**（boot 三测试类 static 块）：类专属 db 文件 + agents 目录整体重建——Windows 打开中的文件不可删，同 db 文件会导致测试类互相踩死

## 四、刻意留白（review 时不要当成缺陷报）

1. **运行时增删改 cron 定义**：四端点只管状态/立即执行/启停，定义 CRUD 归 29/30 节（届时 ScheduledTaskStore 补 unregister/delete，不预留空壳）
2. **分布式协调 / 失败重试告警 / misfire 补跑**：单实例本地锁延续（008 口径）
3. **状态更新与历史写入非原子**：两次写最终一致（单实例接受，⑦ P2）
4. **task_executions 历史清理**：每分钟任务一年 52 万行——清理策略归扩展
5. **技术方案 §9.2 的 updated_at 行**：与 §8.5/课件/DDL 骨架不一致，本节按 10 字段落地——是否回写待拍板（research R12、flow-status 遗留待办 3）
6. **任务级/工具级成败语义**：last_status 只管循环完成与否，工具级成败在 tool_invocations 审计（管理台文案已改「上次完成」表达该语义）
7. **停用状态 next_run_at 显示旧值**：停用保留原值、重新启用后下次执行更新（Clarifications 2026-09-09，接受）
8. **管理台历史明细页**：executions 端点已交付，页面展示归后续迭代

## 五、建议 review 顺序

1. `JpaScheduledTaskStore` + 两实体（先看懂重注册保状态列与状态迁移——重启不失忆的全部秘密在这）
2. `AgentScheduler`（registerAll 冲突检测 → runOnce 启用检查 → executeInternal 锁策略 → runNow）
3. `Profile.Schedule` + `ProfileLoader`（id 补入与 ⑦c 校验，改动面最小的前序改造）
4. `ScheduleApiController` + `ScheduleApiControllerTest`（四端点契约与双信封边界）
5. 测试底座：`ScheduledTaskE2ETest`（五步 + @MockitoBean + 工作区隔离模式）→ `MultiAgentIsolationTest`
6. 前端 `SchedulesView.vue`（写操作页 + 提示文案）+ `axion-admin-ui` skill ⑦d 条款
7. `schema.sql` + `application.yaml`（DDL 骨架逐字 + 白名单「加」语义）

## 六、当前验收状态

- **全量门禁实跑通过**（2026-09-10）：`mvn clean verify -Dskip.npm` 10 模块 BUILD SUCCESS（Spotless/SpotBugs/ErrorProne/Checkstyle/PMD 全过；storage 23 + core 67 + web 17 + cli 9 + boot 5 gate 内测试全绿；两个 IT @Tag integration 真 key 留人工）
- **管理台演示闭环**（2026-09-10 用户实跑）：weather-demo Agent（工作区）→ 立即执行 → 真 ReAct（http_get 成功 + notify 渠道未配如实报告）→ 列表 run_count/last_status 正确 → 停用/启用切换正确
- **落位拍板 A**：ScheduledTaskStore 接口随实现落 storage（停止清单 3 触发，Maven 依赖方向不可逆）——spec/需求文档 ⑧/技术方案 §8.5 三处同步
- **S5 analyze F1 修正**：runNow 排队（lock()）/到点跳过（tryLock）语义按需求文档 ⑦a 落地
- **演示期处置两条**（2026-09-10 拍板）：①「上次成功」→「上次完成」文案（任务级/工具级成败语义）②停用行操作列提示文案——均已落 SchedulesView.vue 并重建
- **剩余待办**：① SchedulerFlowIT / RestartRecoveryIT 真 key 手动跑 ② Demo 前置六项打勾 ③ 技术方案 §9.2 updated_at 回写拍板 ④ notify 渠道 team-lark 行 INSERT（真 webhook）
