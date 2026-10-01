# Feature Specification: Tool 体系（内置 Tool + Plugin Tool 接入）

**Feature Branch**: `005-tool`

**Created**: 2026-09-05

**Status**: Draft

**Input**: 需求文档 docs/requirements/005-tool.md（课件第 20 节：Tool 体系——ToolRegistry + 内置六件 + MCP 方式二 + @Tool 方式三 + 004 NotifyTools 接线）

## User Scenarios & Testing *(mandatory)*

### User Story 1 - chat 里 Agent 真的去查了天气（Priority: P1）

用户在 `axion chat` 里问"北京今天天气怎么样"，Agent 调 `http_get` 真发请求拿回数据，再生成穿搭建议。到这一步，配合 Provider（001）、ReAct（002）、CLI（003）、Notify（004），Demo 一的对话版闭环——Agent 从"只会想和说"变成"能动手干事"。

**Why this priority**: 本课存在的意义（课件 §一/§五：Tool 是 Agent 的手）；Demo 一对话版是本节验收锚点，也是前四课积木的第一次完整联动。

**Independent Test**: 全 mock 单测验证每个工具的 execute 链路（正常跑通 + 越界被拦）；人工部分 `axion chat` 真模型问天气（依赖真 key + 真网络，无 key 如实记待办）。

**Acceptance Scenarios**:

1. **Given** 内置六个工具已注册进工具集，**When** 用户执行 `axion tool list`，**Then** 六个工具全部可见（含 name/description）
2. **Given** chat 对话中 LLM 发起 http_get 调用，**When** ToolExecutor 按名调度，**Then** 工具 execute 首行先过 sandbox.enforce、通过后真发请求、结果回填对话
3. **Given** 一次工具调用（成功或失败），**When** 执行结束，**Then** `tool_invocations` 落账（复用 ToolExecutor 既有路径，本课不新增审计逻辑）

---

### User Story 2 - 业务方零代码扩展（方式一主推；本课交付方式二 MCP 地基）（Priority: P2）

业务方不写代码：写一个 Agent 目录 + 在 `mcp_servers.yaml` 配置复用的社区 MCP server，LLM 自己理解任务、自己组合调用。本课交付方式二的底座：启动时连接 MCP server、`tools/list` 拉工具、包装成 AxionTool 注册——方式一的完整验证依赖真模型与真 server（31 节日报 Agent 硬依赖）。

**Why this priority**: Plugin Tool 是"OS"区别于单点 Agent 框架的关键（业务能力是业务方接进来的，不是底座写死的）；三档接入里方式二是本课唯一可落代码验证的扩展路径（方式一依赖 29/31 节生态、方式三依赖业务方）。

**Independent Test**: mock MCP client 单测：tools/list 返回的工具被包装注册；execute 转发参数原样、结果包 ToolResult（失败 retryable）；连接失败只 WARN 不炸。

**Acceptance Scenarios**:

1. **Given** `mcp_servers.yaml` 配置了一个可达的 stdio MCP server，**When** 启动时 connectAll 执行，**Then** 它的工具以 AxionTool 身份注册进 ToolRegistry
2. **Given** 配置里的某个 MCP server 失联，**When** 启动时连接失败，**Then** 只 WARN 跳过它的工具，其余工具照常注册、启动不炸（坑十三）
3. **Given** LLM 调用一个 MCP 工具，**When** 执行，**Then** 参数经 JSON-RPC 原样转发、结果包成 ToolResult 回传

---

### User Story 3 - 越界会被拦（Priority: P2）

Agent 想读白名单外的文件、跑白名单外的命令、请求白名单外的域名：`execute` 首行 `sandbox.enforce(...)` 先校验，不过直接抛异常，IO 根本不会发生。本课以 mock Sandbox 验证拦截链路（坑十：enforce 先于 IO）；三层白名单实现归第 23/24 节。

**Why this priority**: 安全是地基不是补丁（业界调研 §5.6：最小权限、沙箱强制）；坑十的顺序纪律是 002 contracts/sandbox.md 行为不变量三的第一次全量落地（六个工具全部首行 enforce）。

**Independent Test**: 每个工具"正常跑通 + 越界被拦"两条单测（mock Sandbox：InOrder 断言 enforce 先于 IO；违规抛 SandboxViolationException 时 IO 零发生）。

**Acceptance Scenarios**:

1. **Given** 一次 File/Shell/Http 工具调用，**When** execute 执行，**Then** 先执行 enforce 校验、通过后才发生真实 IO（顺序颠倒即测试红）
2. **Given** 目标不在白名单（mock 拒绝），**When** execute 执行，**Then** 抛 SandboxViolationException、IO 零发生、审计落 success=false

---

### User Story 4 - 每个 Agent 只拿到声明的工具（Priority: P2）

Profile 的 `tools` 字段限定子集：ToolRegistry 按它过滤，不多一个、少一个都是错。这是核心阶段 Tool 治理的雏形（完整 allow/deny 策略扩展阶段补）。

**Why this priority**: 最小权限原则的落地形态；也是 20~23 节 PermissiveSandbox 安全窗口（拍板方案 A）期间唯一的工具收敛手段——保守 Profile 纪律依赖过滤的精确性。

**Independent Test**: ToolRegistryTest：按 Profile.tools 过滤后子集精确匹配（多一个和少一个都断言失败）；重名注册拒绝 + WARN；Profile 声明未知名工具 → 启动校验报错（001 同款纪律）。

**Acceptance Scenarios**:

1. **Given** Registry 注册了 8 个工具、Profile 声明 tools: [read_file, notify]，**When** 过滤，**Then** 返回恰好 2 个、不多不少
2. **Given** Profile 声明了 Registry 不存在的工具名，**When** 启动校验，**Then** 明确报错（不静默少一个）
3. **Given** 两个来源注册了同名工具，**When** 后注册发生，**Then** 明确拒绝 + WARN（不静默覆盖）

---

### User Story 5 - MCP server 失联不拖垮底座（Priority: P3）

配置里的某个 MCP server 挂了：启动时只 WARN 跳过它的工具，其余工具照常注册，Axion 照常起——外部依赖的可用性不是自己的可用性。

**Why this priority**: 隔离外部失败是本课稳定性核心（课件 §四最值钱测试之二）；优先级低于主链路但属必修。

**Independent Test**: mock 连接失败：connectAll 不抛异常；好 server 的工具照常注册、坏 server 的工具不注册。

**Acceptance Scenarios**:

1. **Given** 两个 MCP server 一个可达一个失联，**When** 启动 connectAll，**Then** 好 server 工具注册、坏 server 只 WARN、整体启动成功

---

### Edge Cases

- **重名注册**：内置工具与 MCP 工具同名 → 明确拒绝 + WARN（不覆盖——防意外遮蔽内置工具）
- **Profile 声明未知名工具**：启动校验明确报错（001 provider 引用校验同款纪律）
- **shell 超时**：默认 30s（实现级明确）→ 强制销毁进程 + 明确报错；退出码非 0 → `ToolResult.failure`（stdout/stderr 进 errorMessage）
- **http 响应体超限**：>1MB（实现级明确）→ 明确报错，防超长响应撑爆上下文
- **write_file 父目录不存在**：明确报错，不递归建目录；已存在文件覆盖
- **契约三件套缺失**：任何注册工具 name/description/inputSchema 任一为空 → 参数化测试立刻红（坑十二）
- **方式三扫描 API 核实不到**（Spring AI 1.1.8 实测形态不确定）→ 降级为装配处手动注册并在 flow-status 记录（不静默硬接）
- **PermissiveSandbox 安全窗口**（20~23 节，拍板方案 A）：白名单未生效期间执行保守 Profile 纪律——不建议 shell/http_post 进任何 Agent 的 tools 声明；24 节替换后解除

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: 系统 MUST 提供 `ToolRegistry`：三种来源的工具（内置、方式二 MCP、方式三 @Tool 包装）统一注册为 AxionTool；按 Profile.tools 字段过滤出 Agent 可用子集，MUST 不多不少；重名注册 MUST 明确拒绝 + WARN（不静默覆盖）；Profile 声明未注册的工具名 MUST 启动校验报错（不静默少一个）
- **FR-002**: 系统 MUST 提供内置文件工具三件 `ReadFileTool`/`WriteFileTool`/`ListDirTool`（实现级明确类名）：execute 首行 `sandbox.enforce(FILE_READ/FILE_WRITE, path)` 先于 IO（坑十），通过才读写；路径以参数传入不硬编码；write_file 覆盖已存在文件、父目录不存在明确报错
- **FR-003**: 系统 MUST 提供 `ShellTools`（shell）：执行 bash 命令带超时（默认 30s 实现级明确，超时强制销毁进程 + 明确报错）；execute 首行 `sandbox.enforce(SHELL_COMMAND, 命令)`；退出码非 0 → `ToolResult.failure`（stdout/stderr 进 errorMessage）
- **FR-004**: 系统 MUST 提供 `HttpTools`（http_get/http_post）：用 RestClient 发请求；execute 首行 `sandbox.enforce(HTTP_REQUEST, url)` 先于请求；响应体上限 1MB（超限明确报错）；http_post 支持 JSON body（contentType 默认 application/json，form/文件上传明确不做）
- **FR-005**: 系统 MUST 提供 MCP 方式二接入：`McpClientService` 启动时读 `.axion/mcp_servers.yaml`（name/transport/command/env，stdio 起步），连接后 `tools/list` 拉取、每个工具包装成 `AxionTool` 注册；**连接失败只 WARN 跳过、其余照常注册、启动不炸**（坑十三）；`McpToolAdapter` 执行时 JSON-RPC 转发、结果包 `ToolResult`（失败 retryable=true）；凭证走 `${ENV_VAR}` 占位
- **FR-006**: 系统 MUST 提供方式三接入 `AnnotatedMethodToolAdapter`：启动时扫描容器内 @Tool 注解方法，仅借 Spring AI 做 schema 生成与注册发现（宪法 II），包装成 AxionTool 注册；**执行 MUST 走 ToolExecutor 链路**（包装器 execute 内调方法、返回序列化为文本包 ToolResult、异常原样上抛），MUST NOT 启用 Spring AI 自动执行（坑二）；扫描 API 实施前 H3 核实，核实不到 → 降级装配处手动注册并记录 flow-status
- **FR-007**: 系统 MUST 完成装配改造（004 遗留接线）：`CliAgentConfiguration` 工具集空 Map 换成 ToolRegistry（内置六件 + 方式三 + NotifyTools + MCP 全汇入）；按 004 契约不变量 9 构建 RestClient（Boot 自动配置 builder + connect/read timeout）；`Map.of("webhook", webhookAdapter)` + NotifyChannelRegistry（真实 Repository）显式 @Bean 装配 NotifyTools；**临时 `PermissiveSandbox` @Bean（拍板方案 A）**：全放行、javadoc 标注第 24 节替换为 WhitelistSandbox；**安全窗口纪律**：20~23 节不建议 shell/http_post 进任何 Agent 的 tools 声明
- **FR-008**: 系统 MUST 保证工具契约三件套：任何注册工具 name/description/inputSchema 非空——`AxionToolContractTest` 参数化遍历 Registry 钉死（坑十二），漏实现 getInputSchema 立刻红

### Non-Functional Requirements

- **NFR-001**: 全程同步阻塞，不引入异步编程模型；MCP 走 McpSyncClient 同步门面（业务层零 Reactor 代码——依赖树中框架内部实现不构成违规）；并发由 Java 21 虚拟线程承担（宪法 VII）
- **NFR-002**: 审计 day one：所有工具执行成败都落 `tool_invocations`——复用 ToolExecutor 既有路径，本课不新增审计逻辑、不改 ToolExecutor（宪法 V）
- **NFR-003**: 结构化 JSON 日志沿用既有地基；MCP 失联 WARN 带 server 名；工具入参（URL/路径/命令）不进日志参数（004 NFR-2 口径延续）

### Key Entities *(include if feature involves data)*

- **ToolRegistry**: 统一工具注册表——三来源 AxionTool 集合 + 按 Profile.tools 过滤 + 重名拒绝；`tool list` 命令与后续 `/api/v1/tools` 端点的数据源
- **McpServerConfig**: MCP server 配置行——name/transport/command/env（`.axion/mcp_servers.yaml` 解析产物）
- **PermissiveSandbox**: 临时全放行 Sandbox（拍板方案 A）——第 24 节替换为 WhitelistSandbox，javadoc 标注替换时机
- **AxionTool / ToolResult**: 001 已交付抽象，本课全部工具实现与返回值复用
- **审计记录（tool_invocations）**: 复用既有表与路径，本课零新增

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: `axion chat` 里问天气，Agent 能调 http_get 拿回真实数据并给出穿搭建议——Demo 一对话版闭环（人工真模型；无 key 如实记待办）
- **SC-002**: `axion tool list` 可见全部注册工具（含方式三示例 Bean），与 Registry 全量列表一致
- **SC-003**: 契约三件套 100% 覆盖——参数化测试遍历 Registry，任何工具三件套缺失即红（0 缺失）
- **SC-004**: Profile.tools 过滤 100% 精确——子集恰好等于声明列表，多一个少一个都红（0 偏差）
- **SC-005**: MCP 失联场景启动成功率 100%——外部依赖不可用不成为底座不可用的原因
- **SC-006**: `mvn clean verify` 全绿（本课 8 个测试类 + 001~004 全部回归），无任何跳过/放宽

## Assumptions

- **前序交付物已就位、无缺口**：AxionTool/ToolResult/JsonSchema/ToolSchemaAdapter/Profile.tools（001）、ToolExecutor + Sandbox 接口墙 + contracts/sandbox.md（002）、CliAgentConfiguration 空 Map + InitCommand 模板 + tool list 命令 + SnakeYAML（003）、NotifyTools 五件套 + notify_channels + 契约不变量 9 + spring-web（004）——现状实测确认（2026-09-05）
- **Sandbox 实现后补**：WhitelistSandbox 三层白名单归第 23/24 节（002 FR-7，本节不翻案）；生产接线按拍板方案 A 挂临时 PermissiveSandbox，24 节无缝替换
- **MemoryTools 归 21/22 节**：save_memory/recall_memory 本节不做（技术方案 §6.2 九件里的 Memory 两件后补注册）
- **MCP 生态假设**：核心阶段只做 stdio transport（SSE 放扩展，编程指南 §4.4）；新增 spring-ai-starter-mcp-client 依赖（本地实测 spring-ai-mcp 1.1.8 仅协议壳），实施时 mvn dependency:resolve + jar 反查核实 API
- **平台假设**：生产目标 Linux（K8s/服务器，bash 可用）；Windows 本机测试经 Git Bash 的 bash（003 同款环境口径）
- **安全窗口口径**：20~23 节 PermissiveSandbox 全放行期间执行保守 Profile 纪律（FR-007），内网假设 + 审计留痕兜底，文档诚实说明
- **无前序公共接口改造**：ToolExecutor/AxionTool/Sandbox/NotifyTools 原样使用；唯一改造点 CliAgentConfiguration 工具集注入（003 FR-10 既定口径"第 20 节替换"）
