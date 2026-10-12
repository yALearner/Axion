# Tasks: Agent 动态管理（012-agent-mgmt）

**Input**: Design documents from `/specs/012-agent-mgmt/`

**Prerequisites**: plan.md（R1~R10）、spec.md（6 US）、research.md、data-model.md、contracts/agent-mgmt.md

**Tests**: 需求文档验收标准点名 5 个测试类 + 改造点回归——全部为显式任务（harness 先行，测试任务先于或伴随实现任务）。

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 可并行（不同文件、无依赖）
- **[Story]**: 归属 user story（US1..US6）；Setup/Foundational/Polish 无标签
- 含精确文件路径；任务级 DoD：实现与测试一起落地、红当场修、任务完成勾选 tasks.md

---

## Phase 1: Setup（共享前置）

**Purpose**: 配置键、提示词、错误码——三个互不依赖的横切前置

- [ ] T001 [P] `application.yaml` 增配置键 `axion.author.provider`/`axion.author.model`（注释对齐坑十五语义：provider 缺省取 axion.providers 第一个、model 留空则生成端点可读 503 不发 model=null）：`axion-boot/src/main/resources/application.yaml`
- [ ] T002 [P] `prompts/prompts.md` 增 `AGENT_AUTHOR_PROMPT`（生成用系统提示词：一句话描述 → 合规 AGENT.md 格式说明；实现级明确 8 落位）：`prompts/prompts.md`
- [ ] T003 [P] `ErrorCode` 增 5 条目（`AGENT_EXISTS`→400、`AGENT_NOT_FOUND`→404、`AGENT_INVALID`→400、`GENERATE_INVALID`→400、`AUTHOR_MODEL_MISSING`→503，data-model.md §二口径）：`axion-web/src/main/java/com/axion/web/api/ErrorCode.java`

---

## Phase 2: Foundational（全部 US 的阻断前置）

**Purpose**: core 基础设施（生命周期编排 + 上下文 + 记忆 per-agent + 装配接线）——MUST 完成于任何 US 之前

- [ ] T004 `ToolExecutionContext`（`ThreadLocal<String> agentName` + set/current/clear，javadoc 注明坑十六泄漏纪律）：`axion-core/src/main/java/com/axion/core/ToolExecutionContext.java`
- [ ] T005 `AgentStore`（write/writeAll/archive/delete + 脚手架模板四件（AGENT.md/scripts//skills//REFERENCE.md，R6 口径：provider 缺省 author.provider、tools/schedules 空），workspaceRoot 构造）：`axion-core/src/main/java/com/axion/core/AgentStore.java`
- [ ] T006 [P] `AgentStoreTest`（write 落 `.axion/agents/<name>/AGENT.md` 与手写格式一致、archive 移入 `.axion/archive/`、delete 回滚删除、脚手架四件齐全）：`axion-core/src/test/java/com/axion/core/AgentStoreTest.java`
- [ ] T007 `AgentScheduler.unregisterProfile(Profile)`（遍历 schedules 从 scheduledTasks 取句柄 `cancel(false)` 后移除句柄与 registrations 登记；**不动 taskLocks**——011 留白兑现）：`axion-core/src/main/java/com/axion/core/AgentScheduler.java`
- [ ] T008 [P] `AgentSchedulerRegisterTest` 扩展（unregister 后句柄/登记移除、cancel(false) 调用断言、taskLocks 无条目残留）：`axion-core/src/test/java/com/axion/core/AgentSchedulerRegisterTest.java`
- [ ] T009 `ToolExecutor.execute` 增 agentName 参数（执行前 `ToolExecutionContext.set`、finally `clear`——坑十六；ReActLoop 调用点同步传 `profile.name()`）：`axion-core/src/main/java/com/axion/core/ToolExecutor.java` + `axion-core/src/main/java/com/axion/core/ReActLoop.java`
- [ ] T010 [P] `ToolExecutorTest` 扩展（执行期间上下文可见、结束后清除、工具抛异常也清除——坑十六回归）：`axion-core/src/test/java/com/axion/core/ToolExecutorTest.java`
- [ ] T011 `MemoryService` 增 `readAll(String agentName)` + `MemoryServiceImpl` 实现（委托 store.load 前后临时置入 Agent 名再复原；buildContext 取 `session.profileName()`——读路径不经 ToolExecutor）：`axion-core/src/main/java/com/axion/core/MemoryService.java` + `axion-memory/src/main/java/com/axion/memory/MemoryServiceImpl.java`
- [ ] T012 [P] `MemoryServiceTest` 扩展（readAll(agentName) 路由到对应 Agent 记忆、无泄漏复原）：`axion-memory/src/test/java/com/axion/memory/MemoryServiceTest.java`
- [ ] T013 `MarkdownMemoryStore` 构造改 workspaceRoot、运行时按 `ToolExecutionContext.current()` 解析路径（有上下文 → `agents/<name>/MEMORY.md`；无上下文 → 回退全局 `memory/MEMORY.md`；锁文件随记忆文件同目录——R3 口径）：`axion-memory/src/main/java/com/axion/memory/MarkdownMemoryStore.java`
- [ ] T014 [P] `MarkdownMemoryStoreTest` 扩展（per-agent 落点/回退全局）+ `LongTermMemoryStoreTest` 契约**零改动**回归确认（SPI 三档契约钉死）：`axion-memory/src/test/java/com/axion/memory/MarkdownMemoryStoreTest.java`
- [ ] T014b [P] `MemoryToolsTest` 扩展（save_memory/recall_memory 经 ToolExecutionContext 落 per-agent 路径、无上下文回退全局——改造点回归）：`axion-memory/src/test/java/com/axion/memory/MemoryToolsTest.java`
- [ ] T015 [P] `AgentLifecycleServiceTest`（InOrder 钉顺序：create 四步（坑十八 name 冲突第一步拒一个目录都不写）、注册失败回滚已写目录（坑十一）、create 与 watcher 同一段 register（坑十七）、delete 顺序「注销定时→移出索引→归档」（坑十）、update schedules 先 unregister 后 register；课件 §八两个最值钱测试原文收录）：`axion-core/src/test/java/com/axion/core/AgentLifecycleServiceTest.java`
- [ ] T016 `AgentLifecycleService` 实现（register(agentDir)/create/get/update/delete + 校验钩子注入位（R4）+ generateFiles/saveFiles 方法体留到 US4）：`axion-core/src/main/java/com/axion/core/AgentLifecycleService.java`
- [ ] T017 `CliAgentConfiguration` 装配接线（agentStore、校验钩子=005 `validateToolRefs` 同款逻辑注入、author 配置读取注入、MarkdownMemoryStore 新构造）：`axion-cli/src/main/java/com/axion/cli/CliAgentConfiguration.java`

**Checkpoint**: 地基就绪——三条录入共用的一段注册代码 + 注销能力 + per-agent 记忆上下文全部可编译可测

---

## Phase 3: User Story 1 - 管理台新建 Agent 即上线 (Priority: P1) ⭐ MVP

**Goal**: POST /api/v1/agents（{name, description} → 脚手架 + 派生注册）→ 免重启立即可见；管理台 Agent 列表 + 新建

**Independent Test**: 空工作区 POST 创建带 schedules 的 Agent → GET /agents 立即可见；重名 400 一个目录都不写；注册失败回滚零残留（AgentLifecycleServiceTest 已钉）

### Tests for User Story 1

- [ ] T018 [P] [US1] `AgentApiControllerTest`（standalone MockMvc：create 薄转发、400 `AGENT_EXISTS`、统一 ApiResponse 信封）：`axion-web/src/test/java/com/axion/web/api/AgentApiControllerTest.java`

### Implementation for User Story 1

- [ ] T019 [US1] DTO：`CreateAgentRequest`/`UpdateAgentRequest`/`AgentView`（data-model.md §一字段口径，AgentView 不对前端暴露 api_key）：`axion-web/src/main/java/com/axion/web/api/dto/`
- [ ] T020 [US1] `AgentApiController` +create/get/list（POST /api/v1/agents、GET /api/v1/agents、GET /api/v1/agents/{name}；invoke 不变）：`axion-web/src/main/java/com/axion/web/api/AgentApiController.java`
- [ ] T021 [US1] 管理台 `AgentView.vue`（列表 GET /agents + 新建表单 name/description + 错误态展示 ApiResponse.message + 三态规范）+ `api.js` 增 `apiPost/apiPut/apiDelete`（双信封单一解析点）+ `main.js`/`App.vue` 路由导航：`axion-web/src/main/frontend/src/views/AgentView.vue` 等

**Checkpoint**: US1 独立可验——API 与页面都能新建 Agent、免重启上线

---

## Phase 4: User Story 2 - 丢目录即上线、删目录即注销 (Priority: P1)

**Goal**: WorkspaceWatcher 守护线程：启动全量扫 + WatchService 实时监听 `.axion/agents/`（新增/改 → register(agentDir)；删除 → 注销同一段代码；坏目录 WARN 跳过不拖垮）

**Independent Test**: 运行中手工拷入合法目录 → 几秒内出现在 ProfileRegistry；删除 → 注销；坏目录 → 日志报错、监听不中断（WorkspaceWatcherTest 钉死）

### Tests for User Story 2

- [ ] T022 [US2] `WorkspaceWatcherTest`（丢目录触发 register(agentDir)（坑十七）、删目录触发注销、坏目录 WARN 跳过不拖垮监听、启动全量扫拾取存量）：`axion-core/src/test/java/com/axion/core/WorkspaceWatcherTest.java`

### Implementation for User Story 2

- [ ] T023 [US2] `WorkspaceWatcher` 实现（构造 workspaceRoot+lifecycle；start() = 全量扫（复用 AgentLoader 遍历口径）→ WatchService 注册 ENTRY_CREATE/DELETE/MODIFY → 事件循环；R5 口径：失败 WARN 跳过）：`axion-core/src/main/java/com/axion/core/WorkspaceWatcher.java`
- [ ] T024 [US2] `CliAgentConfiguration` 装配 watcher daemon 线程（命名 `axion-workspace-watcher`，R10 口径）：`axion-cli/src/main/java/com/axion/cli/CliAgentConfiguration.java`

**Checkpoint**: US1+US2 双 P1 完成——API 与丢目录两条路都免重启生效

---

## Phase 5: User Story 3 - API 全生命周期管理 (Priority: P2)

**Goal**: PUT 更新（正文/覆写即时生效、schedules 变更先注销旧再注册新）+ DELETE 删除（注销→移出→归档）+ 查改删不存在 404

**Independent Test**: MockMvc 全序列 create → get → update → delete；更新 schedules 后旧句柄注销断言（lifecycle 单测）；不存在 → 404 统一信封

### Tests for User Story 3

- [ ] T025 [P] [US3] `AgentApiControllerTest` 扩展（update/delete 薄转发、404 `AGENT_NOT_FOUND`、schedules 变更先 unregister 后 register 透传断言）：`axion-web/src/test/java/com/axion/web/api/AgentApiControllerTest.java`

### Implementation for User Story 3

- [ ] T026 [US3] `AgentApiController` +update/delete（PUT/DELETE /api/v1/agents/{name}；404 经 `AgentNotFoundException` → GlobalExceptionHandler）：`axion-web/src/main/java/com/axion/web/api/AgentApiController.java` + 异常类（如 `AgentNotFoundException.java` 落 dto/或 api/ 同目录）

**Checkpoint**: US3 独立可验——REST 全生命周期闭环、错误码口径 2.5 落实

---

## Phase 6: User Story 4 - 按描述重生成 + 文件保存生效 (Priority: P2)

**Goal**: generate-files（一次 LLM 调用落 llm_calls → AgentLoader 解析校验（坑十四）→ 剥 ``` 围栏 → 返回 {相对路径→内容} 不落盘不注册；坑十五 model 未配 503）+ saveFiles（先校验再写盘、重注册写入即生效）+ 工作区文件浏览/编辑（防目录穿越坑十二；AGENT.md 编辑走 update 重注册坑十三）

**Independent Test**: GenerateTest（草稿可解析/不落盘/非法 400/围栏剥离/503）；WorkspaceApiControllerTest（穿越 400 关键回归）；保存后下一轮用新内容

### Tests for User Story 4

- [ ] T027 [P] [US4] `GenerateTest`（generateFiles 产出可被 AgentLoader 解析、只生成不落盘不注册（磁盘零变化断言）、坑十四非法 400 可读原因、``` 围栏剥离、坑十五 model 未配 503、LlmGateway 被调用断言（llm_calls 审计由 001 契约保证——宪法 V 锚点））：`axion-core/src/test/java/com/axion/core/GenerateTest.java`
- [ ] T028 [P] [US4] `WorkspaceApiControllerTest`（tree 结构可钻进 Agent 目录、file 读正常返回、坑十二 `../../etc/passwd` 穿越 400、写盘落对位置、坑十三编辑 AGENT.md 走 update 重注册）：`axion-web/src/test/java/com/axion/web/api/WorkspaceApiControllerTest.java`

### Implementation for User Story 4

- [ ] T029 [US4] `AgentLifecycleService.generateFiles/saveFiles`（LlmGateway 调用 + author 配置 + 解析校验 + 剥围栏 + writeAll 重注册；generate 落 llm_calls 审计由 LlmGateway 契约保证）：`axion-core/src/main/java/com/axion/core/AgentLifecycleService.java`
- [ ] T030 [US4] `AgentApiController` +generate-files/files 两端点（POST /api/v1/agents/{name}/generate-files、POST /api/v1/agents/{name}/files；DTO `GenerateFilesRequest`/`GenerateFilesResponse`/`SaveFilesRequest`）：`axion-web/src/main/java/com/axion/web/api/AgentApiController.java` + dto/
- [ ] T031 [US4] `WorkspaceApiController` + `FileNode`（GET /api/v1/workspace/tree、GET /api/v1/workspace/file?path=、POST /api/v1/workspace/file；防穿越 normalize+startsWith(root)（坑十二）；AGENT.md 走 update（坑十三））：`axion-web/src/main/java/com/axion/web/api/WorkspaceApiController.java`
- [ ] T032 [US4] `AgentDetailView.vue` 基本信息/生成/文件 3 tab（生成：填描述 → generate-files → 每文件可编辑预览 → files 保存并生效；文件：tree 左树 + 右编辑保存；三态规范）：`axion-web/src/main/frontend/src/views/AgentDetailView.vue` + 嵌套路由

**Checkpoint**: US4 独立可验——重生成与文件编辑全链路（人在环）

---

## Phase 7: User Story 5 - 每个 Agent 专属记忆与固定会话 (Priority: P3)

**Goal**: GET /agents/{name}/memory（per-agent 只读）+ 固定会话两端点（GET session / POST session/messages 触发 ReAct）；全局记忆端点退役、管理台长期记忆页移除

**Independent Test**: 固定会话发消息触发 save_memory → 记忆落在该 Agent 目录（ToolExecutionContext 链路）；会话 id 恒定 admin:console:<name>；全局 /api/v1/memory 404（已退役）

### Tests for User Story 5

- [ ] T033 [P] [US5] `AgentApiControllerTest` 扩展（memory 只读返回、session getOrCreate 幂等（恒同一条）、messages 触发 process 透传）：`axion-web/src/test/java/com/axion/web/api/AgentApiControllerTest.java`

### Implementation for User Story 5

- [ ] T034 [US5] `AgentApiController` +memory/session/messages 三端点（GET /agents/{name}/memory → readAll(name)；GET /agents/{name}/session → SessionManager.getOrCreate("admin","console",name)（session_id 拼接只在 SessionManager）；POST /agents/{name}/session/messages → AgentService.process（宪法 VIII）；`SessionView`/`SessionMessageRequest` DTO）：`axion-web/src/main/java/com/axion/web/api/AgentApiController.java` + dto/
- [ ] T035 [US5] `MemoryApiController` 调整（移除全局 GET /api/v1/memory，009 改造点）+ `MemoryApiControllerTest` 同步调整：`axion-web/src/main/java/com/axion/web/api/MemoryApiController.java` + 测试
- [ ] T036 [US5] `AgentDetailView.vue` 会话/记忆 2 tab（会话：气泡 + 输入框发消息；记忆：只读展示）+ `MemoryView.vue` 移除 + `App.vue` 导航更新：`axion-web/src/main/frontend/src/views/`

**Checkpoint**: US5 独立可验——per-agent 记忆与固定会话闭环，全局记忆退役

---

## Phase 8: User Story 6 - 删除可追溯 (Priority: P3)

**Goal**: DELETE 后目录完整保留在 `.axion/archive/`、历史审计（llm_calls/tool_invocations）逐笔可查

**Independent Test**: 带定时任务的 Agent DELETE → 定时不再触发、列表移除、archive 目录存在（AgentStore 落点）；历史审计表记录完好（无删除语句）

### Tests for User Story 6

- [ ] T037 [US6] `AgentApiControllerTest` delete 端到端扩展（DELETE 后 archive 目录存在断言、审计表数据零删除断言、404 语义）：`axion-web/src/test/java/com/axion/web/api/AgentApiControllerTest.java`

### Implementation for User Story 6

- [ ] T038 [US6] delete 链路核对收口（lifecycle.delete 已实现于 T016；本任务核对 archive 路径与审计零删除的端到端语义，无新代码则记录核对结论）：`axion-core/src/main/java/com/axion/core/AgentLifecycleService.java`

**Checkpoint**: US6 独立可验——删除可追溯承诺落实

---

## Phase 9: Polish & Cross-Cutting Concerns

**Purpose**: 全量门禁 + 工程化沉淀

- [ ] T039 [P] 管理台前端构建 `npm run build`（产物落 `axion-web/src/main/resources/static/admin/`；深蓝 token 沿用）：`axion-web/src/main/frontend/`
- [ ] T040 全量 `mvn clean verify`（10 模块 + Spotless/P3C/SpotBugs/FindSecBugs/PMD/ErrorProne）全绿——含前序 001~011 全部测试回归（DoD 1/4）
- [ ] T041 残留核对（全局 /api/v1/memory 与 MemoryView 退役后 grep 零引用；grep 无明文 key 抽查）+ 实施实录沉淀：CLAUDE.md 陷阱表补录本节踩坑（如有）+ `docs/reviews/012-agent-mgmt-review.md` 代码 review 指南（011 模式）+ flow-status.md S6 收尾更新

---

## Dependencies

```
Phase 1 (T001~T003, [P])
  → Phase 2 (T004→T005/T006→T007/T008→T009/T010→T011/T012→T013/T014→T015→T016→T017)
    → Phase 3 US1 (T018→T019→T020→T021)
    → Phase 4 US2 (T022→T023→T024)
      → Phase 5 US3 (T025→T026)
        → Phase 6 US4 (T027/T028 并行 → T029→T030→T031→T032)
          → Phase 7 US5 (T033→T034→T035→T036)
            → Phase 8 US6 (T037→T038)
              → Phase 9 (T039/T040/T041)
```

US1 与 US2 同优先级（P1），US2 依赖 Phase 2 的 lifecycle（register）；US4 依赖 US3（AgentApiController 扩展位）；US5 依赖 Phase 2（ToolExecutionContext/MemoryService）+ US3；US6 依赖 Phase 2（unregister）+ US3（delete 端点）。前端任务（T021/T032/T036）依赖各自后端端点。

## Parallel Examples

- Phase 1：T001/T002/T003 并行（三个不同文件）
- Phase 2：T006/T008/T010/T012/T014/T015 六类测试并行（不同测试文件）；T004/T005/T007 实现任务按依赖序
- Phase 6：T027（core 测试）与 T028（web 测试）并行
- 全流程 [P] 标记共 13 处

## Implementation Strategy（MVP 先行）

1. **MVP = Phase 1+2+US1**（T001~T021）：配置/错误码/提示词 + 生命周期编排 + 注册闭环——"填个名字就上线"机制成立
2. **增量 1 = US2**（T022~T024）：实时监听——"丢目录即上线"补全 P1
3. **增量 2 = US3+US4**（T025~T032）：API 全生命周期 + 重生成/文件——"真能管"
4. **增量 3 = US5+US6**（T033~T038）：per-agent 记忆/会话 + 删除可追溯——"个体性 + 可追溯"
5. **收尾 = Phase 9**：全量门禁 + 工程化沉淀
