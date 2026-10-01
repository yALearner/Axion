# Tasks: ReAct 循环（Agent 大脑）

**Input**: Design documents from `specs/002-react/`（plan.md / spec.md / data-model.md / contracts/ / research.md / quickstart.md）

**Prerequisites**: plan.md ✓、spec.md ✓（六个 user story）、data-model.md ✓、contracts/ ✓（react-loop / session-manager / sandbox）、research.md ✓（R1~R10，G2 拍板已落）

**Tests**: 已明确要求（需求文档验收 harness：7 个测试类 + 坑↔测试对号表，harness 先行）

**Organization**: 按 user story 分组，每个 story 独立可测、可独立交付。

## 格式约定

- `[P]`：可并行（不同文件、无依赖）
- `[USx]`：归属 user story；Setup/Foundational/Polish 不标
- 所有文件路径为仓库根目录相对路径，包名 `com.axion.*`

---

## Phase 1: Setup（共享基础设施）

**Purpose**: 依赖核实与环境准备

- [X] T001 依赖核实（写前 H3 门禁）：对 axion-core 跑 `mvn dependency:tree`，核实锁定 BOM 中承载 `Prompt`/`ChatResponse`/`ToolCall`/`ToolCallingChatOptions`/`ToolDefinition` 的确切 artifact（`spring-ai-model` 或同级数据模型构件）；结论记录到 `specs/002-react/research.md` 的 R1 决策备注，核实不到 → 停下报告
- [X] T002 [P] 在 `axion-core/pom.xml` 增加 Spring AI 数据模型依赖（T001 核实的 artifact；**不含 starter/自动配置/Agent 抽象**——拍板③边界）

---

## Phase 2: Foundational（阻塞性前置）

**Purpose**: 所有 user story 共享的底座类型与改造点落地，完成前不得开始任何 story

- [X] T003 [P] 创建 `Session` 在 `axion-core/src/main/java/com/axion/core/Session.java`——id/profileName/channel/userId/messages 累积容器，`append(Message)` 追加（历史以 001 的 `Message` 承载，框架无关可序列化；字段见 data-model.md）
- [X] T004 [P] **harness 先行**：编写 `SessionManagerTest` 在 `axion-core/src/test/java/com/axion/core/SessionManagerTest.java`——① 同一 (channel,user,profileName) 三元组两次 getOrCreate 返回同一实例（幂等）② 三元组任一不同则不同 Session ③ **session_id 拼接只发生在 SessionManager 一处**（H4 不变量四架构断言）（先写、先跑、确认失败）
- [X] T005 实现 `SessionManager` 在 `axion-core/src/main/java/com/axion/core/SessionManager.java`——`getOrCreate`/`get`/`save`（内存版 save 为 no-op 占位，第 18 节 JPA 化）；`ConcurrentHashMap` 承载；session_id 拼接只此一处
- [X] T006 [P] 创建 `LlmGateway` 端口接口在 `axion-core/src/main/java/com/axion/core/LlmGateway.java`——签名逐字 = `ChatResponse chat(String sessionId, Profile profile, Prompt prompt)`（G2 拍板；契约见 contracts/react-loop.md）
- [X] T007 [P] 迁移 `ToolSchemaAdapter`（G2 拍板改造点一）：`axion-provider/src/main/java/com/axion/provider/ToolSchemaAdapter.java` → `axion-core/src/main/java/com/axion/core/ToolSchemaAdapter.java`（包名改 `com.axion.core`，**逻辑零改动**）；`ToolSchemaAdapterTest` 随迁到 `axion-core/src/test/java/com/axion/core/ToolSchemaAdapterTest.java`；删除 provider 侧两个旧文件
- [X] T008 [P] 改造点二：在 `axion-provider/src/main/java/com/axion/provider/ProviderService.java` 类声明加 `implements LlmGateway`（一行声明，方法零改动；001 既有调用方不受影响）
- [X] T009 [P] 创建 `ToolInvocation` 实体 + `ToolInvocationRepository` 在 `axion-storage/src/main/java/com/axion/storage/`；`axion-storage/src/main/resources/schema.sql` **增量追加** `tool_invocations` DDL（含 `success`/`error_message` 两列，`created_at` ISO-8601 TEXT 复用 `InstantTextConverter`；字段见 data-model.md）
- [X] T010 [P] 创建 `PromptBuilder` 骨架在 `axion-core/src/main/java/com/axion/core/PromptBuilder.java`——`build(Session, Profile)` 返回最小可运行 Prompt（system 段 = `Profile.identity.prompt` + 末尾当前日期时间；历史/长期记忆/工具列表三段留拼接位，US4 填全）

**Checkpoint**: 底座就绪——Session/SessionManager/LlmGateway/PromptBuilder 骨架/审计表 + 两个改造点落地，六个 story 可开始

---

## Phase 3: User Story 1 - 想一步、做一步、看结果，直到给出最终答复（Priority: P1）★ MVP

**Goal**: 主循环七步：多轮工具调用连续完成、中间结果累积、无 tool 调用一轮收尾、坑一死循环兜底、坑六执行权唯一

**Independent Test**: `ReActLoopTest` + `ToolExecutorTest` 全绿（模型/工具全 mock，无网络）

### Tests for US1（harness 先行，先写先红）

- [X] T011 [P] [US1] 编写 `ReActLoopTest` 在 `axion-core/src/test/java/com/axion/core/ReActLoopTest.java`——① 无 tool 调用一轮收尾返回文本 ② 有 tool 调用 → 执行并回填进下一轮 ③ **坑一回归**：模型一直要调工具 → 恰好 10 轮（`verify(gateway, times(10)).chat(...)`）、返回含"达到最大轮数" ④ **坑三回归**：每轮响应与工具结果按序累积进 Session ⑤ **坑六回归**：工具经 ToolExecutor 恰好执行一次，无框架自动执行路径
- [X] T012 [P] [US1] 编写 `ToolExecutorTest` 在 `axion-core/src/test/java/com/axion/core/ToolExecutorTest.java`——按名找工具执行、未知工具名返回失败结果、成功写审计 `success=true`、**坑七回归**：失败也写 `success=false` + 原因、`retryable` 回传、异常不吞、无内部自动重试（mock `ToolInvocationRepository`）

### Implementation for US1

- [X] T013 [P] [US1] 实现 `ToolExecutor` 在 `axion-core/src/main/java/com/axion/core/ToolExecutor.java`——`execute(String sessionId, ToolCall)` → ToolResult；注入 `Map<String, AxionTool>` 工具集（第 20 节换成 ToolRegistry 不改本类）；按名找工具→执行→结果包装；**成败都写 `tool_invocations`**（宪法 V，接线 T009 的 Repository）；**每次执行记录结构化日志**（sessionId/toolName/耗时/成败，敏感参数不入日志）；**不持 Sandbox 引用**（涉外 enforce 由各工具 execute 首行自执行，contracts/sandbox.md）
- [X] T014 [US1] 实现 `ReActLoop` 在 `axion-core/src/main/java/com/axion/core/ReActLoop.java`——`run(Session, String userMessage, Profile)` 七步循环（需求文档核心骨架）；注入 `LlmGateway`/`PromptBuilder`/`ToolExecutor`；`ChatResponse` → core `Message` 私有转换（assistant 含 toolCalls；TOOL 消息含 ToolCallResult）；`maxIterations` 默认 10（`Profile.Settings` 常量，001 已建全）；**每轮循环记录结构化日志**（session.id/轮次/LLM 调用耗时与成败——补足 NFR-2 的 LLM 调用侧日志）；不触发 Spring AI Agent 抽象

**Checkpoint**: US1 独立可测——主循环全链路 mock 跑通（MVP）

---

## Phase 4: User Story 2 - 每步工具执行可审计（Priority: P2）

**Goal**: 成功与失败都落 `tool_invocations`（success/error_message 列真实存在），可关联会话，与 llm_calls 同口径

**Independent Test**: `ToolInvocationRepositoryTest` 全绿 + `ToolExecutorTest` 审计字段断言全绿

### Tests for US2（harness 先行）

- [X] T015 [P] [US2] 编写 `ToolInvocationRepositoryTest` 在 `axion-storage/src/test/java/com/axion/storage/ToolInvocationRepositoryTest.java`——**坑八回归**：测试中执行手工 `schema.sql` 建表（不让 Hibernate 自动建——沿用 001 `LlmCallRepositoryTest` 同款讲究）；`tool_invocations` 可存可读、`success`/`error_message` 两列真实存在、ISO-8601 `created_at` 往返一致

### Implementation for US2

- [X] T016 [US2] 在 `ToolExecutorTest` 补强审计字段级断言：成功路径 sessionId/toolName/inputJson/resultJson/durationMs 字段正确；失败路径"执行抛异常**并且**审计先落了账"（`success=false` + `error_message` 含原因）

**Checkpoint**: US1+US2 独立可测——工具审计双路径可追溯

---

## Phase 5: User Story 3 - 出错时自己纠偏（Priority: P2）

**Goal**: 工具失败结果完整回传模型（含 retryable），下一轮 LLM 自行决定重试/换工具；执行层不自动重试

**Independent Test**: `ReActLoopTest` 纠偏回归用例全绿

### Tests for US3（harness 先行）

- [X] T017 [US3] 在 `ReActLoopTest` 补充纠偏回归：第一轮工具执行失败 → 失败结果（含 `retryable`）完整进入下一轮上下文 → 模型第二轮换工具成功 → 最终答复基于纠偏后结果；断言执行层无自动重试（`verify(tool, times(1)).execute(...)` 恰好一次）

### Implementation for US3

- [X] T018 [US3] 核实并补强 retryable 回传链路：`ToolResult.errorMessage`/`retryable` 完整进入回填的 TOOL 消息内容，不丢失不吞（有缺口才改实现，测试先行已把行为钉死）

**Checkpoint**: US1~US3 独立可测——纠偏链路与无自动重试钉死

---

## Phase 6: User Story 4 - 每轮上下文正确组装（Priority: P3）

**Goal**: Prompt 四部分按序组装（角色+引导+Skill 元数据+日期 / 长期记忆跳过 / 截断历史 / 工具列表）、坑二截断不切 tool 链、坑五无缓存

**Independent Test**: `PromptBuilderTest` + `ContextLoaderTest` 全绿（@TempDir 临时工作区，无网络）

### Tests for US4（harness 先行）

- [X] T019 [US4] 编写 `PromptBuilderTest` 在 `axion-core/src/test/java/com/axion/core/PromptBuilderTest.java`——① 四部分顺序正确 ② **坑二回归**：历史超 N 轮（默认 20）被截断、不切断一轮内的 tool 调用链（TOOL 消息跟随所属 ASSISTANT 响应成组保留）③ system 段末尾含当前日期时间 ④ 长期记忆段未启用时跳过 ⑤ 工具列表为 Function Calling 格式（复用 ToolSchemaAdapter 契约）⑥ 工具列表为空 → prompt 正常组装（无工具段不报错）
- [X] T020 [P] [US4] 编写 `ContextLoaderTest` 在 `axion-core/src/test/java/com/axion/core/ContextLoaderTest.java`——**坑五回归**：改文件后下一次 build 立即读到新内容（无缓存）；显式引用缺失报错；Bootstrap 缺失至少 WARN；skills 软连接元数据（name/description）注入、目录不存在跳过（@TempDir 搭临时工作区）

### Implementation for US4

- [X] T021 [P] [US4] 实现 `ContextLoader` 在 `axion-core/src/main/java/com/axion/core/ContextLoader.java`——构造注入工作区根路径；`load(Profile)` 每轮现读：① `Profile.bootstrap` 引用文件（显式缺失报错、Bootstrap 缺失 WARN）② `.axion/agents/<name>/skills/<name>` 软连接 → 公共 `SKILL.md` frontmatter 的 name/description（宪法 IV：只注入元数据不预载正文，frontmatter 不声明 skills）；无缓存
- [X] T022 [US4] 完善 `PromptBuilder` 在 `axion-core/src/main/java/com/axion/core/PromptBuilder.java`——四段全量组装（骨架 T010 填全）：① system = `identity.prompt` + ContextLoader 产物 + 当前日期时间 ② 长期记忆留拼接位跳过 ③ 最近 N 轮历史（坑二截断语义）④ 工具列表（注入的工具集 → ToolSchemaAdapter 翻译 → Function Calling 格式）

**Checkpoint**: US1~US4 独立可测——上下文组装全链路钉死

---

## Phase 7: User Story 5 - 统一入口编排 + 防死循环兜底（Priority: P3）

**Goal**: AgentService.process 三触发源共用编排、ProfileContext 处理期间可取、坑四 finally 必清、结束后 save(session)

**Independent Test**: `AgentServiceTest` 全绿（mock 循环/会话管理器）

### Tests for US5（harness 先行）

- [X] T023 [US5] 编写 `AgentServiceTest` 在 `axion-core/src/test/java/com/axion/core/AgentServiceTest.java`——① 处理期间 ProfileContext 可取到当前 Profile ② **坑四回归**：处理抛异常时 finally 也把它清掉（`assertNull(ProfileContext.current())`）③ 正常结束 `sessionManager.save(session)` 被调用 ④ Profile 从 `ProfileRegistry.findByName(session.profileName())` 获取

### Implementation for US5

- [X] T024 [P] [US5] 创建 `ProfileContext` 在 `axion-core/src/main/java/com/axion/core/ProfileContext.java`——ThreadLocal\<Profile>（虚拟线程每请求独立）；set/get/clear
- [X] T025 [US5] 实现 `AgentService` 在 `axion-core/src/main/java/com/axion/core/AgentService.java`——`process(Session, String)`：registry 取 Profile → `ProfileContext.set` → `ReActLoop.run` → `sessionManager.save(session)` → **finally `ProfileContext.clear()`**（异常也清）；注入 ProfileRegistry/ReActLoop/SessionManager（宪法 VIII：ReActLoop 不感知消息入口）

**Checkpoint**: US1~US5 独立可测——统一入口 + ThreadLocal 泄漏钉死

---

## Phase 8: User Story 6 - 沙箱接口墙先行（Priority: P3）

**Goal**: Sandbox 纯接口四类型落 axion-tool（宪法 IX 三合一），零实现、无人调用

**Independent Test**: 编译 + 静态检查门禁通过（接口契约本身无行为；接线测试归第 20 节各工具，需求文档已明确）

### Implementation for US6

- [X] T026 [US6] 创建 `Sandbox` 接口、`SandboxAction`、`ActionType`（FILE_READ | FILE_WRITE | SHELL_COMMAND | HTTP_REQUEST）、`SandboxViolationException` 在 `axion-tool/src/main/java/com/axion/tool/`——`enforce(SandboxAction)` 单方法；接口中立（不出现白名单/容器/VM 字样）；字面量照 contracts/sandbox.md 与技术方案 §6.7

**Checkpoint**: 接口墙就位——第 20 节起各工具 execute 首行接入，第 23/24 节 WhitelistSandbox 实现

---

## Phase 9: Polish & Cross-Cutting Concerns

**Purpose**: 全量门禁与收尾证据

- [X] T027 全量验证：`mvn clean verify` 全绿（含静态检查门禁）+ 执行 quickstart.md 的人工核对项（Demo 一对话版真模型跑通记录、人工 review 循环自实现、axion.db 审计核对）；反作弊红线：不删断言、不加 `@Disabled`、不放宽阈值
- [X] T028 [P] 六条全局不变量自查并记录证据：① 涉外 IO 过 `Sandbox.enforce`（本节状态：接口已立，接线位 = 各工具 execute 首行，第 20 节起落地——tasks 已注明）② LLM 成败落 `llm_calls` / 工具成败落 `tool_invocations` ③ grep 无明文 key ④ `session_id` 只在 SessionManager 内拼接 ⑤ 无 Reactor/CompletableFuture/自建线程池 ⑥ 无 Spring AI 自动工具执行路径（`grep -r "executeToolCalls"` 无命中）

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: 无依赖，立即开始
- **Foundational (Phase 2)**: 依赖 Setup（T002 core pom）——**阻塞全部 user story**
- **US1 (Phase 3)**: 依赖 Foundational → **MVP**；无对其他 story 的依赖（PromptBuilder 用骨架 mock）
- **US2 (Phase 4)**: 依赖 Foundational（ToolInvocation 表已在 Phase 2）+ US1 的 ToolExecutor（T013 先于 T016 断言补强）
- **US3 (Phase 5)**: 依赖 US1（ReActLoop/ToolExecutor 行为已就位）
- **US4 (Phase 6)**: 依赖 Foundational（ToolSchemaAdapter/ContextLoader 无依赖；PromptBuilder 完善依赖 ContextLoader）
- **US5 (Phase 7)**: 依赖 US1（ReActLoop）+ Foundational（SessionManager）
- **US6 (Phase 8)**: 依赖 Foundational 即可（纯接口，与其余 story 无耦合）
- **Polish (Phase 9)**: 依赖全部 story

### User Story Dependencies

- US1（P1）：Foundational 后即可开始，独立可测 → **MVP**
- US2（P2）：Foundational 后即可开始，断言补强依赖 US1 的 ToolExecutor
- US3（P2）：依赖 US1 行为，独立可测
- US4（P3）：Foundational 后即可开始，独立可测
- US5（P3）：依赖 US1 + Foundational，独立可测
- US6（P3）：Foundational 后即可开始，独立可测（编译级）

### Within Each User Story

- 测试 MUST 先写并确认失败，再写实现（harness 先行）
- 实现完成即跑该模块测试，红了当场修（任务级 DoD）

### Parallel Opportunities

- Phase 2 的 T003/T004/T006/T007/T008/T009/T010 可并行（T005 等 T004 先红）
- US1 的 T011/T012 测试并行；T013 与 Phase 2 收尾可并行
- US2~US6 的测试任务（T015/T017/T019/T020/T023）在各自 phase 内 [P]
- T021 ContextLoader 与 US3/US5 实现互不依赖，可并行推进

---

## Implementation Strategy

### MVP First (US1 Only)

1. Phase 1 Setup → 2. Phase 2 Foundational（关键，阻塞一切）→ 3. Phase 3 US1 → **停下来独立验证**（ReActLoopTest + ToolExecutorTest 全绿）

### Incremental Delivery

- 地基（Phase 1+2）→ US1 独立验证（MVP：主循环）→ US2（审计落库）→ US3（纠偏回归）→ US4（上下文组装）→ US5（统一入口）→ US6（沙箱接口墙）→ Polish 全量门禁

---

## Notes

- **执行纪律（axion-spec）**：每个 task 开始前跑宪法 9 条速查 + 停止清单预检；任务级 DoD 后**不自动 commit / push / 运行 package.sh**——同步时机由用户决定（本模板原"Commit after each task"条款被本纪律覆盖）
- 测试方法名英文（中文语义用 `@DisplayName` 保留）
- 反作弊红线：测试未全绿不得宣称完成
- 停止清单第 1/2 条判断基准 = 需求文档「交付清单」白名单：ReActLoop、PromptBuilder、ToolExecutor、AgentService、ProfileContext、ContextLoader、Session、SessionManager、LlmGateway、Sandbox/SandboxAction/ActionType/SandboxViolationException、ToolInvocation/ToolInvocationRepository、schema.sql 增量、7 个测试类、ToolSchemaAdapter 迁移（改造点）——清单之外的对外概念一律停下报告
