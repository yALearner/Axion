# Tasks: Agent Provider 模块（对接 LLM）

**Input**: Design documents from `specs/001-provider/`（plan.md / spec.md / data-model.md / contracts/ / research.md / quickstart.md）

**Prerequisites**: plan.md ✓、spec.md ✓（三个 user story）、data-model.md ✓、contracts/ ✓、research.md ✓

**Tests**: 已明确要求（需求文档验收 harness：四个单测类 + ProviderSmokeIT，harness 先行）

**Organization**: 按 user story 分组，每个 story 独立可测、可独立交付。

## 格式约定

- `[P]`：可并行（不同文件、无依赖）
- `[USx]`：归属 user story；Setup/Foundational/Polish 不标
- 所有文件路径为仓库根目录相对路径，包名 `com.axion.*`

---

## Phase 1: Setup（共享基础设施）

**Purpose**: 依赖核实与环境准备

- [X] T001 依赖核实（写前 H3 门禁）：对 axion-provider 跑 `mvn dependency:tree`，核实 ① spring-ai-bom 1.1.8 下 `ChatModel` 调用签名与返回值结构 ② 请求构造中关闭自动 tool 执行的确切写法 ③ DeepSeek/Kimi 的 starter 依赖在锁定 BOM 中存在且能解析下载；结论记录到 `specs/001-provider/research.md` 的 R1 决策备注，核实不到 → 停下报告
- [X] T002 [P] 在 `axion-boot/src/main/resources/application.yaml` 增加全局层配置示例 `axion.providers`（deepseek + kimi，api-key 一律 `${ENV_VAR}` 占位）

---

## Phase 2: Foundational（阻塞性前置）

**Purpose**: 所有 user story 共享的底座抽象与持久化地基，完成前不得开始任何 story

- [X] T003 [P] 创建 `Profile` 记录类（全字段，一次建全）在 `axion-core/src/main/java/com/axion/core/Profile.java`（字段见 data-model.md）
- [X] T004 [P] 创建 `Message` 数据结构（role/content/toolCalls/toolResults）在 `axion-core/src/main/java/com/axion/core/Message.java`
- [X] T005 [P] 创建 `AxionTool` 接口（getName/getDescription/getInputSchema/execute）与 `ToolResult`（success/content/errorMessage/retryable，CLAUDE.md 已定抽象）在 `axion-core/src/main/java/com/axion/core/`
- [X] T006 [P] 创建 `LlmCall` 实体 + `LlmCallRepository` 在 `axion-storage/src/main/java/com/axion/storage/`，手工建表脚本 `schema.sql` 在 `axion-storage/src/main/resources/`（含 `success`/`error_message` 两列，ISO-8601 TEXT 的 `created_at`）
- [X] T007 [P] 创建 `ProviderProperties` 配置类（绑定 `axion.providers` 列表）在 `axion-provider/src/main/java/com/axion/provider/ProviderProperties.java`
- [X] T008 [P] **harness 先行**：编写 `ProfileLoaderTest` 在 `axion-core/src/test/java/com/axion/core/ProfileLoaderTest.java`——frontmatter 全字段解析、引用不存在的 provider 报错清晰、坏文件不阻断其余加载、`${ENV}` 占位从环境变量解析（先写、先跑、确认失败）
- [X] T009 实现 `ProfileLoader`（基础版 deriveProfile + 本节唯一校验项：provider 引用在全局层存在）+ `ProfileRegistry`（内存索引，启动扫描为当前唯一注册路径）在 `axion-core/src/main/java/com/axion/core/`

**Checkpoint**: 底座抽象 + 持久化地基就绪，三个 story 可开始

---

## Phase 3: User Story 1 - 多 Provider 并存、按配置路由（Priority: P1）★ MVP

**Goal**: 双 Provider 显式映射 + 按名路由不串台 + 未知名报错 + 工具只翻译不执行 + 自动执行关闭

**Independent Test**: `ProviderServiceTest` + `ToolSchemaAdapterTest` 全绿（mock 两个 ChatModel，无网络）

### Tests for US1（harness 先行，先写先红）

- [X] T010 [US1] 编写 `ProviderServiceTest` 在 `axion-provider/src/test/java/com/axion/provider/ProviderServiceTest.java`——① 路由不串台（`verify(kimi).call` + `verify(deepseek, never()).call`）② 未知名抛 `ProviderNotFoundException` ③ 自动执行关闭（ArgumentCaptor 断言 `autoExecuteTools=false`）④ 架构断言：无扫描 `ChatModel` Bean 集合的代码路径
- [X] T011 [P] [US1] 编写 `ToolSchemaAdapterTest` 在 `axion-provider/src/test/java/com/axion/provider/ToolSchemaAdapterTest.java`——schema 字段一一对齐、产物不含执行逻辑

### Implementation for US1

- [X] T012 [P] [US1] 实现 `ToolSchemaAdapter` 在 `axion-provider/src/main/java/com/axion/provider/ToolSchemaAdapter.java`（`AxionTool.getInputSchema` → 锁定版本的工具格式，只翻译）
- [X] T013 [P] [US1] 创建 `ProviderNotFoundException` 在 `axion-provider/src/main/java/com/axion/provider/ProviderNotFoundException.java`
- [X] T014 [US1] 实现 `ProviderService` 在 `axion-provider/src/main/java/com/axion/provider/ProviderService.java`——启动按 `axion.providers` 建 `Map<String, ChatModel>` 显式映射（禁止类型扫描）；`chat(sessionId, Profile, Prompt)` 按名取模型、关闭自动执行、同步阻塞调用、统一响应结构；审计调用位预留（US2 接线）

**Checkpoint**: US1 独立可测——双 provider 路由、未知名报错、自动执行关闭、schema 翻译全绿

---

## Phase 4: User Story 2 - 每次模型调用可审计（Priority: P2）

**Goal**: 成功与失败都落 `llm_calls`（success/error_message 列真实存在），失败先落账再上抛，可关联会话

**Independent Test**: `LlmCallRepositoryTest` 全绿 + `ProviderServiceTest` 审计断言用例全绿

### Tests for US2（harness 先行）

- [X] T015 [P] [US2] 编写 `LlmCallRepositoryTest` 在 `axion-storage/src/test/java/com/axion/storage/LlmCallRepositoryTest.java`——测试中执行 `schema.sql` 手工建表（不让 Hibernate 自动建）、实体可存可读、`success`/`error_message` 两列真实存在

### Implementation for US2

- [X] T016 [US2] 在 `ProviderService` 完成审计接线：成功路径把 provider/model/usage/耗时/session_id 落账（`success=true`）；失败路径 catch 中**先落账**（`success=false` + `error_message`）**再把异常原样上抛**
- [X] T017 [US2] 在 `ProviderServiceTest` 补充审计断言用例：失败路径断言"抛了异常**并且**审计先落了账"（`success=false` + 原因），成功路径断言 provider/model/token 字段正确

**Checkpoint**: US1+US2 独立可测——审计双路径可追溯、与 tool_invocations 对称

---

## Phase 5: Polish & Cross-Cutting Concerns

**Purpose**: 冒烟验证与全量门禁收尾

- [X] T018 [P] 编写 `ProviderSmokeIT` 在 `axion-provider/src/test/java/com/axion/provider/ProviderSmokeIT.java`——打 `@Tag("integration")`，读环境变量真 key、真调一次、断言非空响应且 `llm_calls` 新增一条 `success=true`（CI 默认跳过）
- [X] T019 全量验证：`mvn clean verify` 全绿（含静态检查门禁）+ 执行 quickstart.md 的人工核对项（axion.db 核对、`grep -r "sk-"` 无明文、显式映射 review）；反作弊红线：不删断言、不加 `@Disabled`、不放宽阈值

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: 无依赖，立即开始
- **Foundational (Phase 2)**: 依赖 Setup 完成——**阻塞全部 user story**
- **US1 (Phase 3)**: 依赖 Foundational；无对其他 story 的依赖 → **MVP**
- **US2 (Phase 4)**: 依赖 Foundational（LlmCall 已在 Phase 2 交付）；实现上与 US1 的 ProviderService 审计位衔接
- **Polish (Phase 5)**: 依赖 US1+US2

### User Story Dependencies

- US1（P1）：Foundational 后即可开始，独立可测
- US2（P2）：Foundational 后即可开始（LlmCall 基础设施已在 Phase 2），审计接线依赖 US1 的 ProviderService 骨架（T014 先于 T016）

### Within Each User Story

- 测试 MUST 先写并确认失败，再写实现（harness 先行）
- 实现完成即跑该模块测试，红了当场修（任务级 DoD）

### Parallel Opportunities

- Phase 2 的 T003~T008 全部 [P]，可并行
- US1 的 T011~T013 可并行；US2 的 T015 与 US1 实现可并行
- T018 冒烟与 T019 顺序执行

---

## Implementation Strategy

### MVP First (US1 Only)

1. Phase 1 Setup → 2. Phase 2 Foundational（关键，阻塞一切）→ 3. Phase 3 US1 → **停下来独立验证**（ProviderServiceTest + ToolSchemaAdapterTest 全绿）

### Incremental Delivery

- 地基（Phase 1+2）→ US1 独立验证（MVP：路由+翻译+自动执行关闭）→ US2 独立验证（审计双路径）→ Polish 全量门禁

---

## Notes

- **执行纪律（axion-spec）**：每个 task 开始前跑宪法 9 条速查 + 停止清单预检；任务级 DoD 后**不自动 commit / push / 运行 package.sh**——同步时机由用户决定（本模板原"Commit after each task"条款被本纪律覆盖）
- 测试方法名英文（中文语义用 `@DisplayName` 保留）
- 反作弊红线：测试未全绿不得宣称完成
- 停止清单第 1/2 条判断基准 = 需求文档「交付清单」白名单：Profile、ProfileLoader、ProfileRegistry、ProviderService、ToolSchemaAdapter、ProviderNotFoundException、ProviderProperties、Message、AxionTool、ToolResult、LlmCall、LlmCallRepository、schema.sql、application.yaml 配置段——清单之外的对外概念一律停下报告
