# Tasks: Tool 体系

**Input**: Design documents from `specs/005-tool/`（plan.md / spec.md / data-model.md / contracts/tool-registry.md / research.md / quickstart.md）

**Prerequisites**: plan.md ✓、spec.md ✓（5 个 user story）、data-model.md ✓、contracts/ ✓（tool-registry）、research.md ✓（裁决 1~8）

**Tests**: 已明确要求（需求文档验收标准 harness 四块：AxionToolContractTest / ToolRegistryTest / FileToolsTest·ShellToolsTest·HttpToolsTest / McpClientServiceTest·McpToolAdapterTest + AnnotatedMethodToolAdapterTest；前三块纯单测、第四块 mock MCP 连接不碰网）

**Organization**: 按 user story 分组，每个 story 独立可测、可独立交付。

## 格式约定

- `[P]`：可并行（不同文件、无依赖）
- `[USx]`：归属 user story；Setup/Foundational/Polish 不标
- 所有文件路径为仓库根目录相对路径，包名 `com.axion.*`
- **审计口径**（宪法 V）：工具执行成败复用 ToolExecutor 既有路径落 `tool_invocations`——本课**零新增审计逻辑、零改动 ToolExecutor/PromptBuilder（core 零改动）**，由 T012 装配 + T021 核对钉死
- **过滤口径（S4 实测细化）**：PromptBuilder.selectTools（002 已交付）已按 Profile.tools 过滤 + 未知名 WARN（注释明示"注册校验归第 20 节"）——005 的"未知名启动报错"在**装配处**做启动校验（不改 core）；ToolRegistry.filter 作为 Registry 契约 API 交付（ToolRegistryTest 钉语义），生产 prompt 组装沿用 002 现有路径

---

## Phase 1: Setup（共享基础设施）

**Purpose**: 依赖核实 + pom 落地

- [X] T001 依赖核实（写前 H3 门禁）后改 `axion-tool/pom.xml`——增加 `org.springframework.ai:spring-ai-starter-mcp-client`（版本随 spring-ai-bom 1.1.8）。写前核实：`mvn dependency:resolve` 落库后 jar 反查 ① `McpSyncClient`/同步门面类存在 ② stdio transport 类存在 ③ `tools/list`/`callTool` 调用形态；核实不到 → 停止清单第 5 条停下报告（方式三同款纪律）

---

## Phase 2: Foundational（阻塞性前置）

**Purpose**: ToolRegistry + PermissiveSandbox + 契约测试地基，完成前不得开始任何 story

- [X] T002 [P] **harness 先行**：编写 `ToolRegistryTest` 在 `axion-tool/src/test/java/com/axion/tool/ToolRegistryTest.java`——① 三来源工具注册后 `contains/all` 正确 ② **坑十四**：`filter(Profile)` 子集恰好等于声明列表（多一个和少一个都断言失败）③ **重名注册拒绝 + WARN**（FR-1：不静默覆盖）④ **未知名工具报错**（filter 声明未注册名 → 明确异常）（先写、先跑、确认失败）
- [X] T003 [P] 编写 `PermissiveSandbox` 在 `axion-tool/src/main/java/com/axion/tool/PermissiveSandbox.java`——implements `Sandbox`，`enforce` 空实现（全放行）；javadoc 必含「**第 24 节替换为 WhitelistSandbox（002 FR-7）**——仅用于 20~23 节生产接线，替换时调用方零改动，替换后本类删除」
- [X] T004 [P] **harness 先行**：编写 `AxionToolContractTest` 在 `axion-tool/src/test/java/com/axion/tool/AxionToolContractTest.java`——**坑十二参数化**：测试自建 Registry 并注册全部六个内置工具（mock Sandbox 装配）+ NotifyTools/方式三/MCP 包装代表，`@MethodSource` 遍历断言 name/description/inputSchema 非空（漏 getInputSchema 立刻红；新工具自动纳入）（先写、先跑、确认失败）
- [X] T005 实现 `ToolRegistry` 在 `axion-tool/src/main/java/com/axion/tool/ToolRegistry.java`——`ConcurrentHashMap<String, AxionTool>`；`register`（重名拒绝 + WARN）、`contains`、`all`、`filter(Profile)`（精确匹配；声明未注册名 → 明确报错）；纯类交付无组件注解（G4-C1，装配处显式 `@Bean`）

**Checkpoint**: 注册表地基就绪——五个 story 可开始

---

## Phase 3: User Story 1 - chat 里 Agent 真的去查了天气（Priority: P1）★ MVP

**Goal**: 内置六件（FR-2/3/4）+ 装配改造（FR-7）——工具集从空 Map 换成 ToolRegistry 全量、PermissiveSandbox 接线、004 遗留 NotifyTools 接线，`tool list` 可见六件

**Independent Test**: `mvn test -pl axion-tool,axion-cli -am` 全绿（三件测试 + ToolRegistry/契约测试）；人工 `axion tool list` + Demo 一对话版

### Tests for US1（harness 先行，先写先跑确认失败）

- [X] T006 [P] [US1] 编写 `FileToolsTest` 在 `axion-tool/src/test/java/com/axion/tool/builtin/FileToolsTest.java`——三个工具各"正常跑通 + 越界被拦"：**坑十 InOrder 断言 enforce 先于 IO**；mock Sandbox 抛 `SandboxViolationException` → IO 零发生；write_file 覆盖已存在文件、父目录不存在明确报错、不递归建目录；read_file 不存在/不可读明确报错；list_dir 非目录明确报错
- [X] T007 [P] [US1] 编写 `ShellToolsTest` 在 `axion-tool/src/test/java/com/axion/tool/builtin/ShellToolsTest.java`——首行 enforce（InOrder）；**超时强制销毁 + 明确报错**（构造注入小超时如 100ms + 长时间命令如 `sleep 5`，不等真实 30s）；退出码非 0 → `ToolResult.failure`（stdout/stderr 进 errorMessage）；Windows 环境经 Git Bash bash（平台假设）
- [X] T008 [P] [US1] 编写 `HttpToolsTest` 在 `axion-tool/src/test/java/com/axion/tool/builtin/HttpToolsTest.java`——MockWebServer（004 先例）：GET/POST 正常返回；**InOrder enforce 先于请求**；违规请求零发生；**响应体超 1MB 明确报错**；4xx/5xx 异常上抛（坑十一口径）

### Implementation for US1

- [X] T009 [P] [US1] 实现 `ReadFileTool`/`WriteFileTool`/`ListDirTool` 在 `axion-tool/src/main/java/com/axion/tool/builtin/`——implements `AxionTool`（004 机械适配：手写 JsonSchema、无组件注解）；execute 首行 `sandbox.enforce(new SandboxAction(FILE_READ/FILE_WRITE, path))` 先于 IO（坑十）；路径以参数传入不硬编码；入参/出参/行为语义照需求文档「内置工具参数规格」表
- [X] T010 [US1] 实现 `ShellTools` 在 `axion-tool/src/main/java/com/axion/tool/builtin/ShellTools.java`——**构造注入 timeoutMs（G4-C1 修复：超时可测；装配处传 30_000 默认）**；execute 首行 `sandbox.enforce(new SandboxAction(SHELL_COMMAND, command))`；`ProcessBuilder` + `waitFor(timeoutMs)` 超时强制销毁 + 明确报错；退出码非 0 → failure（stdout/stderr 进 errorMessage）
- [X] T011 [US1] 实现 `HttpGetTool`/`HttpPostTool` 在 `axion-tool/src/main/java/com/axion/tool/builtin/`（G4-C2 修复：两个顶层类——一个类只能实现一个 getName()，FileTools 三件同款形态；交付物统称沿用课件 HttpTools 两件）——构造注入 `RestClient` + `Sandbox`；execute 首行 `sandbox.enforce(new SandboxAction(HTTP_REQUEST, url))` 先于请求；GET 返回响应体文本、POST JSON body（contentType 默认 application/json）；1MB 上限超限明确报错
- [X] T012 [US1] 改造 `CliAgentConfiguration` 在 `axion-cli/src/main/java/com/axion/cli/CliAgentConfiguration.java`（003 交付物，FR-7 全量）——① 工具集空 Map → 装配 `ToolRegistry`（内置六件注册；**全量 Map 注入 PromptBuilder/ToolExecutor 沿用 002 现有路径，core 零改动**）② **启动校验**：遍历 ProfileRegistry 各 Profile 的 tools 引用存在性，未注册名明确报错（001 同款纪律；PromptBuilder 的 WARN 保留为兜底）③ `PermissiveSandbox` @Bean ④ 按 004 契约不变量 9 构建 `RestClient`（Boot `RestClient.Builder` + connect/read timeout）+ `Map.of("webhook", webhookAdapter)` + `NotifyChannelRegistry`（真实 Repository）显式 @Bean 装配 `NotifyTools` ⑤ `McpClientService` 接线（T017 完成后补挂）

**Checkpoint**: US1 独立可测——`tool list` 可见六件 + 004 接线闭环（MVP）

---

## Phase 4: User Story 2 - 业务方零代码扩展（方式二地基 + 方式三包装）（Priority: P2）

**Goal**: MCP 方式二（FR-5）+ 方式三包装器（FR-6）——启动连接、tools/list 包装注册、失联隔离、@Tool 扫描包装

**Independent Test**: mock client 单测全绿（坑十三 + 转发 + 包装 + 坑二无自动执行）

### Tests for US2（harness 先行）

- [X] T013 [P] [US2] 编写 `McpClientServiceTest` 在 `axion-tool/src/test/java/com/axion/tool/mcp/McpClientServiceTest.java`——mock client：**坑十三连接失败只 WARN、connectAll 不抛、好 server 工具照常注册、坏 server 不注册**；tools/list 返回的工具逐个包装注册
- [X] T014 [P] [US2] 编写 `McpToolAdapterTest` 在 `axion-tool/src/test/java/com/axion/tool/mcp/McpToolAdapterTest.java`——execute 转发参数原样；结果包 `ToolResult`（成功 success / 失败 `retryable=true`）；getName/getDescription/getInputSchema 映射 tools/list 返回
- [X] T015 [P] [US2] **harness 先行**：编写 `AnnotatedMethodToolAdapterTest` 在 `axion-tool/src/test/java/com/axion/tool/AnnotatedMethodToolAdapterTest.java`——方式三包装器：execute 转发调方法、返回序列化为文本包 `ToolResult`、方法抛异常原样上抛；**坑二断言：无 Spring AI 自动执行路径**（架构断言/grep 测试，001 先例）

### Implementation for US2

- [X] T016 [US2] 实现 `McpServerConfig` 在 `axion-tool/src/main/java/com/axion/tool/mcp/McpServerConfig.java`——不可变 record：name/transport/command/args/env（Map<String,String>，`${ENV_VAR}` 占位解析复用 001 机制）；SnakeYAML 解析（003 依赖先例）
- [X] T017 [US2] 实现 `McpClientService` 在 `axion-tool/src/main/java/com/axion/tool/mcp/McpClientService.java`——`@PostConstruct connectAll()`：读 `.axion/mcp_servers.yaml`（缺失/空列表正常启动，结构非法明确报错）→ 逐个 stdio 连接 → `tools/list` → `new McpToolAdapter(...)` 注册进 ToolRegistry；**连接失败只 WARN 带 server 名、不抛、不拖垮启动**（坑十三）；可 `@Component`（依赖全部就位；G4-C1 口径）
- [X] T018 [US2] 实现 `McpToolAdapter` 在 `axion-tool/src/main/java/com/axion/tool/mcp/McpToolAdapter.java`——implements `AxionTool`；getName/getDescription/getInputSchema 映射 tools/list 返回；execute JSON-RPC 转发（sync 门面）、结果包 `ToolResult`（失败 `retryable=true`）
- [X] T019 [US2] 实现 `AnnotatedMethodToolAdapter` 在 `axion-tool/src/main/java/com/axion/tool/AnnotatedMethodToolAdapter.java`——包装容器内 @Tool 方法（扫描 + schema 生成借 Spring AI，宪法 II）；execute 内调方法、返回序列化为文本包 `ToolResult`、异常上抛；**扫描 API 实施前 H3 核实（T001 同款 jar 反查），核实不到 → 降级为装配处手动注册并在 flow-status 记录**；注册进 ToolRegistry

**Checkpoint**: US2 独立可测——MCP/方式三包装链路全 mock 闭环

---

## Phase 5: User Story 3 - 越界会被拦（Priority: P2）

**Goal**: 坑十全量钉死——六个工具 execute 首行 enforce 先于 IO + 违规零外发（实现与测试已在 US1 落地，本阶段做契约核对）

**Independent Test**: T020 逐项核对通过 + T006/T007/T008 InOrder 用例全绿

### Implementation for US3

- [X] T020 [US3] 坑十契约核对（机器可判核对任务，无需代码）：① grep 六个工具 execute 首行 `sandbox.enforce` 位于 IO 语句之前 ② T006/T007/T008 的 InOrder 断言对号 ③ 与 `specs/005-tool/contracts/tool-registry.md` 不变量 7、002 contracts/sandbox.md 行为不变量三逐字一致 ④ 违规路径审计落 `success=false`（ToolExecutor 既有路径，无新增审计逻辑）；结果记录到本任务勾选说明

---

## Phase 6: User Story 4 - 每个 Agent 只拿到声明的工具（Priority: P2）

**Goal**: 过滤语义全链路一致——Registry 契约 API 与生产 prompt 组装路径口径一致、装配处启动校验落地（实现已在 Foundational/T012，本阶段做一致性核对）

**Independent Test**: T021 核对通过 + T002 坑十四用例全绿

### Implementation for US4

- [X] T021 [US4] 过滤链路一致性核对（机器可判核对任务，无需代码）：① `ToolRegistry.filter(Profile)` 语义（不多不少 + 未知名报错）与 `PromptBuilder.selectTools`（002 现有路径，WARN 兜底）行为口径一致——**core 零改动** ② 装配处启动校验已覆盖全部 Profile 的 tools 引用 ③ 执行层无 Profile 过滤属已知留白（Tool Policy 扩展阶段，技术方案 §6.7 要点二），文档已诚实说明；结果记录到本任务勾选说明

---

## Phase 7: User Story 5 - MCP server 失联不拖垮底座（Priority: P3）

**Goal**: 坑十三闭环——失联隔离（实现与测试已在 US2 落地）+ 人工实机项指引

**Independent Test**: T022 对号核对通过 + T013 失联用例全绿

### Implementation for US5

- [X] T022 [US5] 坑十三对号核对 + 人工指引（机器可判核对任务，无需代码）：① T013 的失联用例与课件 §四最值钱测试逐字对号（"不抛异常 + 好 server 照常注册 + 坏 server 不注册"）② quickstart 人工 #4（不可达 server 实机核验）的执行指引已备（无真 MCP server 时如实记待办）；结果记录到本任务勾选说明

---

## Phase 8: Polish & Cross-Cutting Concerns

**Purpose**: 全量门禁 + 收尾 DoD

- [X] T023 全仓 `mvn clean verify` 全绿——含静态门禁与 001~004 全部回归；红了当场修（不攒、不删断言、不加 @Disabled）
- [X] T024 按 `specs/005-tool/quickstart.md` 执行自动化部分核对 + 输出人工项清单（方式三真跑、**004 遗留 notify 补验**、Demo 一对话版、MCP 失联实机、落库目检、安全窗口留意——执行方法见 `references/manual-acceptance.md`）
- [X] T025 收尾 DoD 七项证据 + 变更总结三段结构（gates.md §收尾 DoD；变更总结直接输出对话，以 `git status --short` / `git diff --stat` 实测为准）

---

## Dependencies & Execution Order

```text
Phase 1   T001（pom + H3 核实）
            │
Phase 2   T002 T003 T004 ── T005（Registry 实现）
            │
Phase 3   T006 T007 T008 ── T009 T010 T011 T012
            │（T009~T011 依赖 T003/T005；T012 依赖 T003/T005/T009~T011）
Phase 4   T013 T014 T015 ── T016 T017 T018 T019（依赖 T005；T017 依赖 T016）
Phase 5   T020（依赖 Phase 3 全绿）
Phase 6   T021（依赖 Phase 2/3 全绿）
Phase 7   T022（依赖 Phase 4 全绿）
Phase 8   T023 T024 T025（依赖全部）
```

## 并行执行示例

- **Phase 2**：T002 / T003 / T004 并行（不同文件）；T005 依赖测试先行确认失败
- **Phase 3 测试三件**：T006 / T007 / T008 并行（不同测试文件）；实现 T009 与 T010/T011 并行（T009 三件独立、T010/T011 互不依赖）
- **Phase 4**：T013 / T014 / T015 并行；T016 与 T019 并行（McpServerConfig 与方式三互不依赖）；T017 依赖 T016
- **Phase 5~7**：T020 / T021 / T022 并行（纯核对任务）

## Implementation Strategy

- **MVP = US1**（+ Foundational）：T001~T012 完成即"内置六件注册 + 004 接线闭环 + tool list 可见"，Demo 一对话版可补跑
- **增量交付**：US1 → US2（MCP + 方式三）→ US3~US5（契约核对对号）→ Polish
- **测试纪律**：harness 先行（T002/T004/T006/T007/T008/T013/T014/T015 先写先跑确认失败）；跑法 `mvn test -pl axion-tool,axion-cli -am` 日常全跑，收尾 `mvn clean verify`
- **边界纪律**：core 零改动（PromptBuilder/ToolExecutor 原样使用）；无新增交付清单之外的对外概念；PermissiveSandbox javadoc 24 节替换标注；安全窗口保守 Profile 纪律；不自动 commit/push/package.sh
