# 定时任务子系统设计文档

> 需求编号：010-scheduler-mgmt | 对应课件第 28 节《全流程串联（二）让底座自己跑得稳》、技术方案 §8.5「状态持久化与可管理（第 28 节补齐）」+ §9.2 两表
> 文档依据：`docs/TechnicalSolution.md` §8.5/§9.2（权威设计源）、`docs/DemandAnalysis.md` §13（Demo 前置）、`docs/AiProgrammingGuide.md`；008-scheduler 的 ⑦d 前置注记（task_id 来源重议——本节到期）
>
> 修订说明（2026-09-09）：① **课件口径（用户拍板）**：整体方案参考 `D:\项目\` 第 28 节课件（新版 PDF 已 PyMuPDF 提取，8 页全文复核）。② **Schedule 补 id（⑦d 候选①落地，用户拍板 A）**：课件 28 节明确 schedules 条目 = `id + cron + zone + message`——008 搁置的「给 `Profile.Schedule` 补 id」到期落地：frontmatter 加 `id:` 键、锁 key 与 task_id 直接用 id（008 派生 key `profileName|cron|message` 退役——改 message 换任务身份的断链风险解除）；存量 agent 无 schedules，零迁移负担。③ **依赖倒置**：`ScheduledTaskStore` 接口 + 值对象落 core、JPA 实现 + 实体仓库落 storage（技术方案 §8.5 明文「契约在 core、实现在 storage」）。④ **形态机械适配**（005/007/008/009 拍板延续）：无组件注解纯类 + 装配处显式 @Bean。⑤ **图名处置**：命名 `docs-scheduler-mgmt-flow.svg`（避开技术方案既有 docs-scheduler.svg 与 008 的 docs-scheduler-flow.svg）。⑥ **稳定性再验而非新增**：notify 推送超时（004 装配处 RestClient timeout 已交付）、外部 MCP 挂了不拖垮启动（005 WARN 跳过已交付）——本节各验一次，不重复实现。⑦ **实施前优化（2026-09-09 四维修正分析：扩展性/可靠性/延展性/稳定性）**：a. **runNow 与到点触发同锁同入口**——「立即执行」与 cron 到点可能同时发生，runNow 若绕开 per-task 锁会并发双跑（审计混乱、会话历史交错）；钉死：runNow 与 runOnce 共用同一把锁、走同一个 `executeInternal` 执行体，runNow 拿不到锁按排队语义（与 009 的 60s 超时兜底自洽）；b. **run 端点同步等待语义**——POST /schedules/{id}/run 同步等待执行完成（复用 009 的 runWithTimeout 60s 上限 + 504 语义）：「立即执行」点下去就是要结果，异步返回则管理台看不到结果；c. **id 缺失 → 启动报错**——frontmatter schedules 条目缺 id 时 AgentLoader 解析处启动报错（不静默、不派生兜底——兜底退回 008 派生 key 即断链风险复活；与 007 zone 校验同款纪律）；d. **axion-admin-ui skill 只读纪律更新**——skill 现行「任何页面不得出现写按钮」与定时任务页（第一个写操作页，课件点名）冲突，skill 需加例外条款：定时任务页允许「立即执行/启用停用」两类写操作，其余页面仍只读；P2 注记——状态更新 + 历史写入非原子（单实例最终一致，接受）；id 冲突报错必须指明冲突的 Profile（两个 Agent 同 id 时运营方猜不出来）；task_executions 历史增长清理归扩展；29/30 节增删改定义需 store 补 unregister/delete（届时拍板，本节注记）。⑧ **落位拍板（2026-09-09 实施期，用户拍板 A）**：`ScheduledTaskStore` 接口与值对象由「core」修正为「与实现同落 storage」——Maven 依赖方向 storage 不得依赖 core（core→storage 既有，反向即循环引用、整个多模块构建报错），原字面在架构上不可行；与 `SessionRepository` 同构（core 消费 storage 接口）；依赖倒置语义不变（调用方依赖接口、JPA 实体封装实现内不上浮）。技术方案 §8.5 同步修正。

## 背景与价值

上一节把人推主干拉通了——但那只证明"被人调用时是对的"。底座真正的价值在于**没人值守时也持续正确**（课件 §一）：定时任务不只"到点跑一下"，而是一个完整子系统（能定义、能自己跑、记得住、能管理）；进程 kill 重启后什么都不丢；多个 Agent 并存互不打架。这三根支柱是 Agent OS 与"一个 Agent Demo"的分水岭——Demo 只要演示几分钟活着就行，底座要在没人盯着的时候也持续正确（课件 §一）。

方法仍是上一节的对账法：定时链路 = 人推链路换了「触发头」（AgentScheduler）、多了「推送尾」（notify），中间引擎完全复用——拿一次真实触发从进到出走一遍、逐张表核对痕迹（课件 §一）。

25 节的定时只在内存注册 cron，重启后靠重扫 Profile 恢复——定义不丢，但「跑过几次、上次成功没、下次几点」这些状态与历史一重启就没了。本节把状态与历史落 SQLite（重启不丢），并做成管理台的一等公民（课件 §一/§2.1③④）。

## 用户场景

**场景一（本节验收场景）：运营方管得了定时任务**——管理台「定时任务」页看全部任务（Profile/cron/下次触发/上次结果/次数/状态），每行「立即执行」「启用·停用」按钮：立即执行不用等 cron（无视启用状态）；停用后到点不再触发、也不记执行历史（课件 §2.1④）。

**场景二：定时链路逐表对账**——定时每两分钟触发的测试 Profile（消息「查北京天气推送穿搭」，渠道指向测试 webhook）自己跑一轮：`sessions` 里 scheduler 会话被复用（连续触发两次仍一条、历史变长）；`llm_calls` 两条（一轮调工具、一轮组织答复）；`tool_invocations` 两条（http_get + notify）全成功；webhook 真收到消息体——**不多不少**（课件 §2.1②）。

**场景三：重启不失忆**——跑几轮对话、攒记忆、定时至少触发一次后 kill 进程，重新 serve：GET /sessions/{id} 完整历史、GET /memory 核心记忆、GET /schedules 任务状态与历史（run_count/上次结果/下次触发）、llm_calls 跨重启不断档——四样全部原样（课件 §2.2）。

**场景四：多 Agent 不打架**——两个差异明显的 Profile（A 只文件工具、B 只 HTTP 工具）同实例并存：工具隔离（A 的对话里拿不到 B 独有工具）、会话隔离（各自会话互不串）、定时隔离（A 的定时抛异常，B 的下个触发点照常）——三条边界都守得住（课件 §2.3）。

**场景五：Demo 前置六项打勾**——http.allowed_domains 含天气 API 域名 + webhook 域名（+新闻源域名按需）、notify_channels 已配、MCP 声明可查、MEMORY.md 有偏好、定时配置（cron+显式时区）核对过、跨重启验证过——31 节两个 Demo 的地基一次配齐（课件 §2.5）。

## 功能需求

> 从课件第 28 节与技术方案 §8.5/§9.2 提炼：交付物列是本节对外概念的白名单，清单之外的新增对外概念必须停下报告。

| 编号 | 需求 | 交付物（落位模块） | 来源 |
|------|------|-------------------|------|
| FR-1 | **两张表（schema.sql 手工增量，坑八口径）**：`scheduled_tasks`（task_id 主键 / profile_name / cron / zone / message / enabled / next_run_at / last_run_at / last_status / run_count——注册时写、每次触发更新）与 `task_executions`（id 主键 / task_id / session_id / started_at / success / error_message / duration_ms——成功失败都记，宪法 V 同源） | schema.sql（axion-storage） | 课件 §2.1③；技术方案 §8.5/§9.2 |
| FR-2 | **`ScheduledTaskStore` 接口（storage，落位拍板 ⑧）**：`register` / `recordExecution` / `isEnabled` / `setEnabled` / `list` / `executions` + 值对象 `ScheduledTaskView` / `TaskExecutionView`（storage）；`JpaScheduledTaskStore` + `ScheduledTask` / `TaskExecution` 实体与仓库（storage）——定义源仍是 skill 的 schedules，两张表只存「状态 + 历史」 | ScheduledTaskStore + 两值对象 + JPA 实现 + 实体仓库（全落 storage） | 技术方案 §8.5 明文（⑧ 修正）；课件 §2.1③ |
| FR-3 | **AgentScheduler 改造（008 交付物）**：`registerAll()` 注册每条 cron 的同时把任务登记进 `scheduled_tasks`（含算出的 `next_run_at`）；执行入口拆成「看启用状态 → 真正执行」——enabled=false 到点直接跳过、不执行、不记历史；每次真正执行成功失败都写 `task_executions`、并更新任务的 last_run/last_status/run_count/next_run；新增 `runNow(taskId)`（管理台立即执行：手动触发一次、不等 cron、无视启用状态）；**⑦a：runNow 与 runOnce 共用同一把锁、走同一个 `executeInternal` 执行体**——「立即执行」与 cron 到点并发时同任务不双跑（runNow 拿不到锁按排队语义，与 009 的 60s 超时兜底自洽） | AgentScheduler 改造（axion-core，008 交付物） | 课件 §2.1③④；技术方案 §8.5；⑦a |
| FR-4 | **`Profile.Schedule` 补 `id`（⑦d 候选①拍板 A）**：frontmatter schedules 条目加 `id:` 键（`id + cron + zone + message` 四字段——课件 28 节明文）；AgentLoader 解析带 id；**锁 key 与 task_id 直接用 id**（008 派生 key 退役）；注册时 id 冲突启动报错（**报错指明冲突的 Profile**——两个 Agent 同 id 时运营方猜不出来，⑦ P2）；**⑦c：id 缺失 → 启动报错**（AgentLoader 解析处校验，不静默、不派生兜底——兜底退回 008 派生 key 即断链风险复活）；CLAUDE.md 核心数据模型 schedules 示例同步 | `Profile.Schedule`（002 交付物改造）+ AgentLoader（003 交付物）+ CLAUDE.md | 课件 §2.1①；⑦d 拍板 A；⑦c |
| FR-5 | **`ScheduleApiController` 四端点（axion-web，统一 /api/v1 前缀 + 双信封：成功 ApiResponse / 错误 ErrorResponse 单出口）**：GET /schedules（列表：任务/Profile/cron/下次触发/上次结果/次数/启用与否）、GET /schedules/{id}/executions（执行历史）、POST /schedules/{id}/run（立即执行——**⑦b：同步等待执行完成，复用 009 的 runWithTimeout 60s 上限 + 504 语义**）、PUT /schedules/{id}（启用/停用）；任务不存在 → 404；AgentScheduler 与 ScheduledTaskStore 都在 core，web 可注入 | ScheduleApiController + DTO（axion-web） | 课件 §2.1④；技术方案 §8.5；⑦b |
| FR-6 | **管理台「定时任务」页**（009 交付物扩展）：列表 + 每行「立即执行」「启用·停用」按钮——26 节只读管理台**第一个写操作页**；复用 axion-admin-ui skill（设计 token/双信封请求封装/三态规范） | 前端工程（axion-web/src/main/frontend 增一页） | 课件 §2.1④ |
| FR-7 | **Demo 前置环境**：`http.allowed_domains` 加三样域名（① 天气源 api.open-meteo.com ② 团队通知 webhook 域名（按实际渠道：飞书 *.feishu.cn / 企业微信 qyapi.weixin.qq.com）③ 新闻源域名按需）；file/shell 白名单保持「全部拒绝」（两个 Demo 用不上）；notify_channels 配好（手动 notify 一次群收得到）；测试 Profile 配 schedules（含 id + 显式时区） | application.yaml（axion-boot）+ .axion 工作区配置 | 课件 §2.5 |
| NFR-1 | 全程同步阻塞，不引入异步模型（宪法 VII）；定时链路 = 人推链路换触发头、加推送尾——中间引擎完全复用 | — | 宪法 VII；课件 §一 |
| NFR-2 | 单任务失败不拖调度器（008 坑三延续）；**停用即不跑、不记历史**（启用检查在记历史之前） | — | 课件 §2.1③；008 契约 |
| NFR-3 | 稳定性打磨再验（⑥，非新增）：notify 推送超时（004 装配处 timeout）、外部 MCP 挂了 WARN 跳过不拖垮启动（005）、error_message 可读（"域名不在白名单内: evil.com"非堆栈）、一次触发日志一条主线（按会话 id 串起） | — | 课件 §2.4 |

### 核心代码骨架（课件 28 节骨架一致，形态机械适配）

```sql
-- schema.sql 增量（坑八：手工维护，测试与生产同一份脚本）
CREATE TABLE IF NOT EXISTS scheduled_tasks (
    task_id TEXT PRIMARY KEY,
    profile_name TEXT NOT NULL,
    cron TEXT NOT NULL,
    zone TEXT,
    message TEXT NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    next_run_at TEXT,
    last_run_at TEXT,
    last_status TEXT,
    run_count INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS task_executions (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    task_id TEXT NOT NULL,
    session_id TEXT,
    started_at TEXT NOT NULL,
    success BOOLEAN NOT NULL,
    error_message TEXT,
    duration_ms BIGINT
);
```

```java
// axion-storage：com.axion.storage —— 契约与实现同落 storage（⑧ 落位拍板：storage 不得依赖 core，与 SessionRepository 同构）
public interface ScheduledTaskStore {
  void register(ScheduledTaskView task, Instant nextRunAt);
  void recordExecution(TaskExecutionView execution);
  boolean isEnabled(String taskId);
  void setEnabled(String taskId, boolean enabled);
  List<ScheduledTaskView> list();
  List<TaskExecutionView> executions(String taskId);
}

// AgentScheduler 改造（008 交付物，FR-3 + ⑦a）：
//   registerAll：taskScheduler.schedule(...) 同时 store.register(taskId, 算出的 nextRunAt)
//   runOnce：先 store.isEnabled(taskId)——停用直接 return（不执行、不记历史）
//   executeInternal：真正执行（锁/三元组/process/失败日志）→ 成败都 store.recordExecution + 更新任务状态
//   runNow(taskId)：手动触发一次，无视启用状态——⑦a 与 runOnce 共用同一把锁、同一 executeInternal（并发不双跑）
```

```java
// axion-web：com.axion.web.api —— 四端点（双信封，009 契约）
@RestController
@RequestMapping("/api/v1/schedules")
public class ScheduleApiController {
  // GET /          → ApiResponse.ok(store.list())
  // GET /{id}/executions → ApiResponse.ok(store.executions(id))（任务不存在 → 404）
  // POST /{id}/run → ⑦b 同步等待：runWithTimeout(() -> scheduler.runNow(id), 60s) → ApiResponse.ok（超时 → 504）
  // PUT /{id}      → { enabled: true/false } → store.setEnabled(id, enabled) → ApiResponse.ok
}
```

### 本节交付物清单（Spec-Kit 拆解锚点 / axion-spec 交付清单比对基准）

- **代码**：schema.sql 两表；`ScheduledTaskStore` 接口 + `ScheduledTaskView`/`TaskExecutionView` 值对象 + `JpaScheduledTaskStore` + `ScheduledTask`/`TaskExecution` 实体仓库（全落 storage，⑧）；`AgentScheduler` 改造（登记/启用检查/执行历史/runNow，008 交付物）；`ScheduleApiController` + DTO（axion-web）
- **测试**：`ScheduledTaskE2ETest`（mock、gate 内无 key：登记/立即执行/落库/停用全流程）、`SchedulerFlowIT`（@Tag integration 真 key 链路对账）、`RestartRecoveryIT`（@Tag integration 重启四样恢复）、多 Agent 隔离测试（工具/会话/定时三边界）
- **表**：scheduled_tasks + task_executions（手工建表脚本增量）
- **配置**：http.allowed_domains 三域名 + notify_channels + 测试 Profile schedules（含 id）
- **改造点**：`Profile.Schedule` 补 id（002 交付物，拍板 A）+ AgentLoader 解析 + `AgentScheduler`（008 交付物）+ CLAUDE.md schedules 示例同步 + 管理台前端（009 交付物加一页）+ **axion-admin-ui skill 只读纪律更新（⑦d：定时任务页「立即执行/启用停用」两类写操作例外条款）**
- **前端**：管理台「定时任务」页（列表 + 立即执行/启用停用——第一个写操作页）

![定时任务子系统全链路：skill schedules 定义（id+cron+zone+message，⑦d 拍板 A 补 id）→ AgentScheduler 注册时登记 scheduled_tasks（含 next_run_at）→ 到点 runOnce 先查 enabled（停用即跳不记历史）→ 真正执行（锁/三元组/process 复用 008）→ 成败都写 task_executions + 更新任务状态 → 管理台定时任务页（第一个写操作页）四端点 GET 列表/executions、POST run 立即执行、PUT 启用停用 → 重启后定义重扫、状态历史仍在（RestartRecoveryIT）；多 Agent 三边界隔离再验；Demo 前置三域名白名单](../../website/public/images/docs-scheduler-mgmt-flow.svg)

## 明确不做

> 来源：课件 §2.1①「不新增定义源」、技术方案 §8.5「核心阶段 vs 扩展阶段的边界」、008 明确不做延续。

- **运行时增删改 cron 定义**：核心阶段 schedules 定义只能写在 AGENT.md frontmatter（29/30 节 Agent 管理端点同批补齐）——本节四端点只管「状态查看/立即执行/启用停用」，不含定义 CRUD
- **分布式协调（选主/分布式锁/租约）**：单实例本地锁延续（008 口径）；多实例归属扩展阶段与「状态外置」一起做
- **失败自动重试/告警、misfire 补跑**：核心阶段「失败不崩、留痕可查」就够（008 延续）
- **Cron 表达式运行时校验增强**：frontmatter 的 cron 非法仍是启动报错（008 ⑦b 口径延续），不做编辑期校验（无编辑入口）
- **任务定义的唯一性治理**：id 冲突启动报错（FR-4，报错指明冲突 Profile），不做自动合并/改名
- **task_executions 历史清理**：每次触发一条历史，长期增长（每分钟任务一年 52 万行）——核心阶段接受，清理策略归扩展（⑦ P2）
- **状态更新与历史写入的事务性**：两次 DB 写非原子，中间崩溃可能历史有、状态没更新——单实例核心阶段接受最终一致（⑦ P2），不做分布式事务
- **29/30 节的定义增删改**：需 ScheduledTaskStore 补 unregister/delete 方法（core 接口扩展，届时拍板）——本节六方法不预留空壳（⑦ P2）

## 验收标准

### 自动化部分（harness 承载）

| 测试类 | 覆盖的验收点 |
|--------|-------------|
| `ScheduledTaskE2ETest`（mock provider、gate 内无 key，@SpringBootTest + 临时 SQLite） | 课件原文五步：① 临时工作区放 mock 且带 schedules（含 id）的 Agent（cron 远期，靠 runNow 手动触发不等时间）② 启动即登记 → GET /schedules 有任务、run_count=0、enabled=true ③ POST run 立即执行 → 走真实 ReAct ④ 断言落库：run_count=1、last_status=success、executions 一条成功、GET /memory 查得到写入 ⑤ PUT 停用 → 列表显示已停用；**停用后到点不触发、不记历史** |
| `SchedulerFlowIT`（@Tag("integration")、真 key） | 链路对账：scheduler 会话复用（连续两次仍一条）/ llm_calls 恰 2 条 / tool_invocations 恰 2 条（http_get+notify）全成功 / webhook 真收到；失败路径：webhook 域名改白名单外 → Sandbox 拦下、tool_invocations 留 success=false、调度器没死（下个触发点照常） |
| `RestartRecoveryIT`（@Tag("integration")） | 跑对话+攒记忆+触发定时后 kill → 重启：GET /sessions/{id} 完整历史 / GET /memory 核心记忆 / GET /schedules（run_count/上次结果/下次触发）/ llm_calls 跨重启不断档——四样全恢复 |
| 多 Agent 隔离测试 | 两差异 Profile（A 文件工具、B HTTP 工具）：工具隔离（A 拿不到 B 独有工具）/ 会话隔离（GET /sessions 各自会话不串）/ 定时隔离（A 定时抛异常 B 下个触发点照常） |
| `ScheduleApiControllerTest`（standalone MockMvc，009 同款） | 四端点契约：列表/executions/run/put 的双信封 + 404 映射（任务不存在） |
| `AgentSchedulerTest` 增补（008 测试类） | 登记断言（register 含 nextRunAt）/ 停用跳过不记历史 / runNow 无视启用 / 成败都写 executions 与状态更新 / **⑦a 并发回归：runNow 与到点触发同时到达时同任务不双跑（executeInternal 串行，008 虚拟线程锁模拟同款）** / **⑦c 回归：id 缺失 → 启动报错** |

最值钱回归（课件 §2.1③ 原文，实现之前写最划算）：

```java
@Test
void 停用后到点不触发且不记历史() {
    store.setEnabled("weather-8am", false);
    scheduler.runOnce(profile, schedule);          // 到点触发
    verify(agentService, never()).process(any(), any());   // 不执行
    verify(store, never()).recordExecution(any());         // 不记历史
}
```

跑法：`mvn test` 日常全跑（ScheduledTaskE2ETest 无 key 进 gate）；全量 `mvn clean verify` 收尾——全量全绿，不写死用例数（007 ⑦e 口径）；两个 IT 打 @Tag("integration") 真 key 手动跑（manual-acceptance 方法论）。

### 人工部分（做完怎么验）

- **定时真能推（真 key 链路）**：配定时 Profile（消息「查北京天气推送穿搭」+ 测试 webhook）→ 真等一轮或 runNow → 逐表对账 + webhook 收到 + 会话复用（SchedulerFlowIT 的手工复验）
- **重启对账**：kill → serve → 四样核对（RestartRecoveryIT 的手工复验）
- **多 Agent 三边界**：两差异 Profile 交替使用核对
- **Demo 前置六项打勾**：三域名白名单 / notify_channels 手动推一次 / MCP tool list / MEMORY.md 偏好 / 定时配置时区 / 跨重启验证

## 依赖与假设

### 前序交付物（已就位，本节直接依赖）

- **008-scheduler**：`AgentScheduler`（registerAll/runOnce/lockFor/scheduledTasks 句柄——改造点）、`Profile.Schedule` 三字段（改造点补 id，拍板 A）、⑦c 句柄 Map（本节 runNow 消费——**实现级明确**：runNow 需按 taskId 找回注册的 (Profile, Schedule)，008 的 scheduledTasks 只存 ScheduledFuture——需补 taskId→Runnable/注册信息映射）、⑨ 系列口径（锁 key 退役为 id）
- **009-web-service**：双信封契约（成功 ApiResponse/错误 ErrorResponse 单出口）、四端点 Controller 先例（standalone MockMvc 测试模式）、管理台前端 + axion-admin-ui skill、AdminSpaConfig（SPA 回落——新页面的路由）
- **004-notify / 005-tool**：notify 超时（装配处 RestClient timeout 已交付）、MCP 容错（WARN 跳过已交付）——⑥ 再验不新增
- **002-react**：`Profile.Schedule` 所在（补 id 改造）、AgentLoader 解析 schedules（003 交付，解析加 id）

**现状确认（2026-09-09 实测）**：schema.sql 无两表 ✓；ScheduledTaskStore 不存在 ✓；`Profile.Schedule` 为 cron/zone/message 三字段无 id ✓；存量 agent（weather/default）无 schedules——补 id 零迁移 ✓；WebhookNotifyAdapter 无 timeout 字段（装配处 RestClient 设超时，004 交付口径）✓。

### 前序缺口（H0 依赖检查）

无——依赖全部实测就位；`Schedule` 补 id 与 `AgentScheduler` 改造为经拍板的改造点。

### 改造点（经拍板允许修改的前序公共接口）

- **`Profile.Schedule` 补 `id`**（002 交付物，拍板 A）：record 加 id 字段（首位）；波及 AgentLoader 解析 + 全仓 `new Profile.Schedule(...)` 构造点 + CLAUDE.md 核心数据模型示例 + 各测试 Profile 的 schedules
- **`AgentScheduler`**（008 交付物）：登记/启用检查/执行历史/runNow + taskId→注册信息映射
- **schema.sql**（003 交付物）：增量加两表
- **管理台前端**（009 交付物）：加「定时任务」页（含路由与导航项）
- 其余前序公共接口零改动：`ScheduledTaskStore` 是新增接口（storage，⑧ 落位拍板），不动 `MemoryService`/`ToolRegistry` 等

### 外部依赖与假设

- **零新第三方依赖**：JPA/Spring MVC/JDBC 全部既有栈
- **课件口径（用户拍板 2026-09-09）**：整体方案参考 28 节课件；Schedule 补 id 拍板 A
- **跨节契约**：本节的 `ScheduledTaskStore` 与四端点是 29/30 节 Agent 管理（增删改定义时复用登记/注销路径）与 31 节 Demo 的消费契约；task_id = frontmatter id 此后不变
- **跑通标准**：ScheduledTaskE2ETest 进 gate 无 key 全绿 + 两个 IT 真 key 手动跑通 + Demo 前置六项打勾
