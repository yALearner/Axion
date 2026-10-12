# Phase 0 Research: 012-agent-mgmt

> 决策记录格式：Decision / Rationale / Alternatives considered。全部为实现级明确项（设计文档已列 9 条，此处逐条定案）。无外部新技术——JDK WatchService、ThreadLocal、MockMvc 均为既有栈内组件，无 NEEDS CLARIFICATION 遗留。

## R1. 编排者落位：AgentLifecycleService / WorkspaceWatcher / AgentStore → axion-core

- **Decision**: 三件全部落 `axion-core`；生成调用经 `LlmGateway` 端口（001 交付的依赖倒置端口，签名与 `ProviderService.chat` 逐字一致），装配时注入 ProviderService 实例。
- **Rationale**: 依赖方向铁律——core 不得反向依赖 provider；且 WorkspaceWatcher 必须在 chat/serve/gateway 三种模式都常驻，装配点在 axion-cli 的 CliAgentConfiguration，其依赖面必须可达（core 最稳）。落 web 则 CLI 模式不可达。
- **Alternatives considered**: 落 axion-web（编排者是 API 编排，但 watcher 需跨模式常驻，否决）；落新模块（违反 9 模块约束与宪法 IX 精神，否决）。

## R2. ToolExecutionContext 置入点：ToolExecutor.execute 增 agentName 参数

- **Decision**: `ToolExecutor.execute(String sessionId, String agentName, ToolCall call)`——置入 `ToolExecutionContext` 于工具执行前、finally 清除（坑十六）。ReActLoop 调用点同步传 `profile.name()`。
- **Rationale**: 课件 5.2.1 明言「ToolExecutor 执行工具前置入 profile.name()、执行后清除」——置入点在 ToolExecutor 内部最符合课件口径；改签名是 002 交付物的扩展（改造点已在需求文档点名，非越权修改）。
- **Alternatives considered**: ReActLoop 置入（ToolExecutor 零改动，但课件口径不符、且读路径与写路径的置入职责分居两处）；ToolResult 回传（侵入更大，否决）。

## R3. per-agent 记忆路径解析：MarkdownMemoryStore 构造改 workspaceRoot

- **Decision**: `MarkdownMemoryStore(Path workspaceRoot)`——运行时按 `ToolExecutionContext.current()` 解析：有上下文 → `workspaceRoot/agents/<name>/MEMORY.md`；无上下文 → `workspaceRoot/memory/MEMORY.md`（回退全局，行为与改造前一致）。锁文件随记忆文件同目录。SqliteMemoryStore/Mem0MemoryStore 零改动。
- **Rationale**: `LongTermMemoryStore` SPI（append/load/recallByKeyword）与三档契约测试一行不改的承诺；默认 Markdown 档变 per-agent（课件明示）。ThreadLocal 读取是同步模型下的安全方式（宪法 VII）。
- **Alternatives considered**: 按 name 多实例化 store（破坏单例装配与 SPI 契约测试）；store 增 name 参数（SPI 签名变更，违反零改动承诺，否决）。

## R4. tools 引用告警运行时校验：装配层钩子注入

- **Decision**: `AgentLifecycleService` 构造注入一个校验钩子（形态：`Consumer<Profile>` 或等价函数式接口）；CliAgentConfiguration 装配时注入 005 `validateToolRefs` 同款逻辑（ERROR 日志、不阻断注册——与启动路径口径一致）。core 不感知 ToolRegistry。
- **Rationale**: 011 债② 处置——校验必须落在同时看得到 Profile 与 ToolRegistry 的装配层；core 不得反向依赖 tool（宪法 IX 精神）。钩子是既有依赖倒置模式（LlmGateway 先例）。
- **Alternatives considered**: lifecycle 落 web 后直接调 ToolRegistry（违反 R1 落位结论，否决）；只在启动扫（债② 不复，否决）。

## R5. 监听竞争窗口与平台差异

- **Decision**: ① 事件处理 try/catch——注册失败记 WARN 跳过、不拖垮监听线程；目录"写一半"产生的坏事件由下一次事件（写完成）自然重试拾取。② macOS WatchService 不监听子目录内文件改动（实录）——编辑 `agents/<name>/AGENT.md` 必须显式走 `AgentLifecycleService.update` 重注册（坑十三），其余文件直接写盘。③ 全量扫与监听之间存在窗口：监听线程启动顺序 = 先全量扫（复用 `AgentLoader.loadAll` 同款遍历）→ 注册 WatchService → 处理后续事件；扫描期间发生的变化依赖目录级事件补收（WatchService 目录事件含子目录创建/删除）。
- **Rationale**: 课件明示的守护线程容错口径（"单个坏目录不拖垮监听"）+ macOS 差异实录；目录级（而非文件级）监听把平台差异收窄到"文件内容编辑"一种场景。
- **Alternatives considered**: 文件级递归注册监听（复杂度高、跨平台更不稳，否决）；事件重试队列（异步模型苗头，违反宪法 VII 精神，否决）。

## R6. 脚手架模板内容（POST /agents {name, description}）

- **Decision**: 模板产出四件：`AGENT.md`（frontmatter：name、description（来自请求）、identity.agent_name=name、identity.prompt=默认人格、provider=axion.author.provider（缺省取 axion.providers 第一个）、model=axion.author.model（留空则省略该键）、tools=空列表、schedules=空；正文=模板任务指令骨架）、`scripts/`（空目录占位）、`skills/`（空目录占位）、`REFERENCE.md`（字段说明模板）。创建后即注册（无 schedules 则只进注册表）。
- **Rationale**: 课件 5.1「后台按模板脚本架出完整目录（模板内内容）」——可复现、不依赖模型；"用大模型生成"下沉为 generate-files（5.2.4）。tools/schedules 留空是最小权限起点（用户后续在详情页加）。
- **Alternatives considered**: 模板只写 AGENT.md 光杆（课件明言"完整目录"含 scripts//skills//REFERENCE.md，否决）；模板预置 tools（扩大默认权限面，违反最小权限，否决）。

## R7. 错误码新增值（2.5 口径：code 各自区分）

- **Decision**: `ErrorCode` 新增：`AGENT_EXISTS`（name 已存在→400）、`AGENT_NOT_FOUND`（查/改/删不存在→404）、`AGENT_INVALID`（AGENT.md 字段非法/解析失败→400）、`GENERATE_INVALID`（生成产物非法→400，带可读原因）、`AUTHOR_MODEL_MISSING`（axion.author.model 未配置→503）。全部走既有 `ApiResponse`/`ErrorResponse` 信封与 `GlobalExceptionHandler`。
- **Rationale**: 课件 2.5「name 已存在、AGENT.md 字段非法、provider 不存在都是 400，code 各自区分；查/改/删不存在 404；生成非法 400 可读原因；不发明新状态码」。
- **Alternatives considered**: 复用现有 code（区分度不够，验收要求"code 各自区分"，否决）；新 HTTP 状态码（课件明示不发明，否决）。

## R8. 固定会话两端点实现

- **Decision**: `GET /agents/{name}/session` = `SessionManager.getOrCreate("admin", "console", name)` 返回 SessionView（sessionId + profileName + 最近 ≤100 条消息，历史截断口径沿用 002）；`POST /agents/{name}/session/messages {content}` = `AgentService.process(session, content)`（与 invoke 同一引擎，宪法 VIII）。session_id 拼接规则（`sessionIdOf`）零改动，只固定 channel/user。
- **Rationale**: 课件 5.2.2「getOrCreate 幂等 → 恒为同一条 admin:console:<name>」+ 宪法 VIII 三触发源同一引擎。
- **Alternatives considered**: 新建 SessionManager 旁路 API（违反不变量④「session_id 只在 SessionManager 拼接」，否决）。

## R9. 管理台前端结构

- **Decision**: `AgentView.vue`（列表 GET /api/v1/agents + 新建表单 name/description + 删除二次确认 + 错误态展示 ApiResponse.message）+ `AgentDetailView.vue`（嵌套路由 `/agents/:name`，5 tab：基本信息（PUT）/生成（generate-files → 每文件可编辑预览 → files 保存）/文件（tree + file 读 + file 写保存）/会话（GET session + POST messages，气泡+输入框）/记忆（GET /agents/{name}/memory 只读））。`api.js` 增 `apiPost/apiPut/apiDelete`（同一双信封解析点，009 拍板 B）。`MemoryView.vue` 与导航项移除；ProfilesView/SessionsView/定时任务/状态页保留。样式沿用深蓝 token（2026-10-01 拍板）与三态规范。
- **Rationale**: 课件 5.4 最终形态（无独立工作区菜单、详情 5 tab）；只读纪律仅放行本节点名写操作（新建/编辑/删除/保存/发消息/生成）。
- **Alternatives considered**: 独立"工作区"页（原始设计口径，已被实现回写取代，否决）；单页全量展示（5 tab 信息量大，否决）。

## R10. WorkspaceWatcher 装配与生命周期

- **Decision**: 在 `CliAgentConfiguration` 装配：构造 `WorkspaceWatcher(workspaceRoot, lifecycle)`，启动一个 daemon 线程（`Thread.setDaemon(true)` + 命名 `axion-workspace-watcher`）——与 `ThreadPoolTaskScheduler` 同类的基础设施守护线程（008 先例）。watcher 自身负责：`AgentLoader.loadAll` 同款全量扫（逐目录调 lifecycle.register，坏目录记 WARN 跳过）→ `WatchService` 注册 `.axion/agents/`（ENTRY_CREATE/ENTRY_DELETE/ENTRY_MODIFY）→ 事件循环（坑十七：新增/改 → register(agentDir)；删除 → 注销同段代码）。
- **Rationale**: 宪法 VII（守护线程不进请求链路）+ 008 调度线程先例；装配与 AgentScheduler 同层。
- **Alternatives considered**: Spring @Scheduled 轮询（非实时、浪费轮询开销，否决）；WebFlux 事件流（违反宪法 VII，否决）。

## 门禁结论

无 NEEDS CLARIFICATION 遗留；无新增第三方依赖（WatchService 为 JDK 内置）；R1~R10 全部与宪法 9 条无冲突。
