# Feature Specification: ReAct Runtime（第一周：对接 LLM + ReAct 循环）

**Feature Branch**: `001-react-runtime`

**Created**: 2026-08-24

**Status**: Draft

**Input**: User description: "Week 1: ReAct runtime — Provider 抽象 + 自实现 ReAct Loop；`axion chat` 多轮对话，Agent 调 HTTP Tool 完成简单任务（涉及 axion-core / axion-provider / axion-channel-cli / axion-cli）"

## Clarifications

### Session 2026-08-24

- Q: `max_history_turns` 中"一轮"如何界定，截断历史时如何保证不切断 Tool 调用链？ → A: 方案 A——一轮 = 一条用户消息；保留最近 N 条用户消息及其后的全部 assistant/tool 消息，绝不从工具调用链中间截断
- Q: HTTP 域名白名单（`http.allowed_domains`）配置在哪里，未配置时默认行为是什么？(FR-015) → A: 方案 C + fail-closed——全局 `.axion/config.yaml`（sandbox 段）+ Agent 级 `AGENT.md` frontmatter；任何未配置的域名一律禁止访问；Agent 级一旦声明即**完全替代**全局列表（覆盖语义），Agent 级未声明时使用全局列表；两级均未配置时默认拒绝一切外网请求
- Q: 在 CLI Channel 中，`session_id` 的 user 组成部分取什么值？(FR-020) → A: 方案 A——channel 固定为 `cli`，user 取当前操作系统用户名（`user.name`）；**已记录演进决策：后续里程碑迁移到方案 C**（可配置覆盖 + 缺省回落 OS 用户名），因 C 的回落默认值即 A 的行为，迁移对存量会话与审计记录无影响
- Q: `http_get` 收到非 2xx 状态码时，`ToolResult.success` 应为 `true` 还是 `false`？(FR-014) → A: 方案 A——非 2xx 一律 `success=false`（content 仍带状态码与响应体）；4xx 及其他非 2xx（如 3xx）不重试、直接返回失败（`retryable=false`）；5xx 由工具**内部自动重试**（最多 3 次、短暂间隔），全部失败后返回 `success=false` + `retryable=true`
- Q: SC-004"抽测 10 组 ≥3 轮多轮对话、引用正确率 100%"用自动化测试验证，还是人工 Demo 验收？(SC-004) → A: A+B 结合——**必须**提供自动化测试用例（10 组抽测，阈值放宽为 ≥9/10 组通过，容忍 LLM 不确定性）；同时保留第一周 Demo 的人工抽测验收

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 配置 Provider 并通过 CLI 多轮对话 (Priority: P1)

企业开发者（用户）在环境变量里配好 LLM API key、在 Agent 目录里声明 provider 后，用 `axion chat` 跟 Agent 进行多轮对话，Agent 能连贯应答并记住上文。用户不关心底层调的是哪家模型。

**Why this priority**: 对接 LLM 是五大核心能力的地基，没有它 ReAct、Memory、Tool 全部无从谈起；这是整个 Axion 的最小可用起点。

**Independent Test**: 配置一个 OpenAI 兼容 Provider（DeepSeek 或 Kimi，API key 走环境变量），执行 `axion chat --profile <name>` 完成 3 轮以上连贯对话即可验证，不依赖其他任何能力。

**Acceptance Scenarios**:

1. **Given** 环境变量配置了有效的 API key 且 `.axion/agents/<name>/AGENT.md` frontmatter 声明了对应 provider，**When** 用户执行 `axion chat --profile <name>`，**Then** 进入交互对话状态，输入消息后收到 LLM 回复
2. **Given** 对话进行中，**When** 用户先自我介绍、随后追问"我刚才说了什么"，**Then** Agent 的回复正确引用前文（多轮上下文保持）
3. **Given** 配置的 API key 缺失或无效，**When** 用户启动 chat 或发送消息，**Then** 给出指明具体环境变量/Provider 的清晰报错，不静默失败
4. **Given** LLM 调用失败（网络中断或配额耗尽），**When** Agent 处理消息，**Then** 用户看到明确的失败信息，进程不崩溃，可继续下一条输入

---

### User Story 2 - Agent 通过 ReAct 循环自主调用 HTTP Tool 完成任务 (Priority: P1)

Agent 收到一个需要外部数据才能回答的任务（如查天气）时，自主决定调用 `http_get` 工具、拿到数据、继续推理，最终给出基于真实数据的答复——全程用户不指挥。

**Why this priority**: ReAct 循环是 Agent 的大脑，是"从 chatbot 到 Agent"的分水岭；第一周的验收 Demo（`axion chat` 多轮对话 + Agent 调 HTTP Tool）就靠它。

**Independent Test**: 让 Agent 完成一次"查询公开 API 并总结"的任务（如天气查询），观察其自主发起 Tool 调用并基于返回数据作答，全程无需用户介入。

**Acceptance Scenarios**:

1. **Given** Profile 的 `tools` 列表包含 `http_get`，**When** 用户提出需要外部数据才能回答的问题，**Then** Agent 自主发起 `http_get` Tool 调用且执行成功
2. **Given** Tool 执行成功且结果已回填，**When** LLM 继续推理，**Then** Agent 输出基于真实数据的最终答复
3. **Given** LLM 某轮响应不含 Tool 调用，**When** ReAct 循环处理该响应，**Then** 直接作为最终响应返回，循环结束
4. **Given** Agent 持续发起 Tool 调用达到 `max_iterations`（默认 10），**When** 循环达到上限，**Then** 强制结束并返回清晰提示，不挂死、不无限循环
5. **Given** LLM 请求调用一个未注册的 Tool 名称，**When** 执行器查找该 Tool，**Then** 返回带错误信息的执行结果回填对话，Agent 可据此修正
6. **Given** `http_get` 的目标域名不在白名单内，**When** Agent 发起该 Tool 调用，**Then** 执行被拒绝，Agent 收到带错误信息的失败结果，不静默放行

---

### User Story 3 - 初始化工作区并管理多个 Agent 目录 (Priority: P2)

用户用一条命令初始化工作区，通过子命令创建、列出、查看、删除 Agent 目录（`AGENT.md`）；多个 Agent 在同一实例上并存、互不影响。

**Why this priority**: "一个目录 = 一个 Agent" 是 Axion 配置化定位的核心，多 Agent 并存是"OS"的最小体现；它同时是 User Story 1/2 的前置（没有 Agent 目录就没有可对话的 Agent）。

**Independent Test**: `axion init` 后执行 `axion profile create` 创建两个声明不同 provider 的 Agent，分别用 `--profile` 对话，验证各自按自己的配置运行。

**Acceptance Scenarios**:

1. **Given** 当前目录没有 `.axion/`，**When** 执行 `axion init`，**Then** 创建 `.axion/` 工作区（子目录 + 三个 Bootstrap 文件）；重复执行不覆盖已有内容（幂等）
2. **Given** 工作区已初始化，**When** 执行 `axion profile create <name>`，**Then** 在 `.axion/agents/<name>/` 生成最小 `AGENT.md` 模板（frontmatter + 正文）
3. **Given** 两个 Agent 目录分别声明不同 provider/模型，**When** 分别用 `--profile` 与其对话，**Then** 各自按自己的 provider 配置发起 LLM 调用，显式映射不串号
4. **Given** 某个 Agent 目录 frontmatter 非法（如 provider 不存在），**When** 系统启动，**Then** 记录错误日志且不阻断其他 Agent 正常使用

---

### User Story 4 - CLI 多轮会话与会话上下文管理 (Priority: P2)

用户在 `axion chat` 内维护一个 Session：对话历史按轮次累积、按配置截断，`--message` 支持单条消息调用后退出，`/quit` 退出交互。

**Why this priority**: Session 是对话的载体；核心阶段内存版跑通"对话历史累积 + 截断"逻辑，为第四周 SQLite 持久化铺路，也直接支撑 User Story 1 的多轮连贯性。

**Independent Test**: chat 内连续进行超过 `max_history_turns` 的对话，验证历史按配置截断且最近轮次连贯；`--message` 单条调用返回结果后进程退出。

**Acceptance Scenarios**:

1. **Given** 用户进入 chat，**When** 连续多轮输入，**Then** 每轮消息按序追加到 Session 对话历史
2. **Given** 对话轮数超过 `max_history_turns`（默认 20），**When** 组装 prompt，**Then** 仅注入最近 N 轮（每轮 = 一条用户消息及其后的全部 assistant/tool 消息），早期对话被截断、system prompt 始终保留、工具调用链不被切断
3. **Given** 用户执行 `axion chat --message "..."`，**When** 命令执行，**Then** 输出单条回复后进程退出
4. **Given** 用户输入 `/quit`，**When** chat 收到该输入，**Then** 退出交互（本里程碑 Session 为内存态，进程结束后不恢复）

---

### Edge Cases

- LLM 返回空内容或无法解析的响应：报错并提示用户重试，不输出空回复
- Tool 执行超时或抛异常：返回 `success=false` 的 ToolResult 回填对话，Agent 可自行重试或换工具
- `http_get` 返回非 2xx 状态码：4xx 及其他非 2xx 直接失败（不重试）；5xx 工具内部自动重试最多 3 次、仍失败才返回失败 ToolResult；失败时状态码与响应体同样回填给 Agent
- LLM 单轮响应包含多个 Tool 调用：核心阶段按顺序逐个执行
- 用户输入空消息：不触发 LLM 调用，友好提示
- 未指定 `--profile` 而工作区有零个或多个 Agent：给出明确提示（列出可用 Agent 或报错）
- `AGENT.md` 缺 `name` 或正文为空：启动校验时报错并记日志，该 Agent 不可用
- 审计记录写入 SQLite 失败：不影响对话主流程，记错误日志并继续

## Requirements *(mandatory)*

### Functional Requirements

**Provider 抽象（核心能力一）**

- **FR-001**: 系统必须维护 provider name 到 LLM 连接的**显式映射表**，不得靠扫描容器内同类型 Bean 区分 Provider
- **FR-002**: Agent 通过 Profile 的 `provider.name` + `model` 引用模型；同一 Agent 换 Provider 只改配置，不改 Agent 正文
- **FR-003**: API key 通过环境变量 `${ENV_VAR}` 占位注入，不得明文写在 `AGENT.md`；配置加载时做必填校验，缺失或非法时给出指明具体变量的清晰报错
- **FR-004**: 每次 LLM 调用必须记录结构化日志，包含：provider、model、prompt/completion/total tokens、耗时、所属 Session 标识
- **FR-005**: Provider 故障直接报错给 Agent，核心阶段不做 fallback、circuit breaker、hedge racing
- **FR-006**: 核心阶段至少跑通一个 OpenAI 兼容协议 Provider（DeepSeek 或 Kimi），映射表结构支持后续增加更多 Provider
- **FR-007**: 系统必须只用 Spring AI 的协议转换能力，禁用其自动 tool 执行（Tool 调度与执行完全由 ReAct 循环控制）

**ReAct 循环（核心能力二）**

- **FR-008**: ReActLoop 必须自实现，不得依赖 Spring AI 的 Agent 抽象
- **FR-009**: 循环算法：用户消息追加会话 → 组装 Prompt → 调用 LLM → [无 Tool 调用 → 返回最终响应] / [有 Tool 调用 → 执行 → 结果回填 → 继续循环]
- **FR-010**: 迭代上限 `max_iterations`（默认 10，Profile 可覆盖），达到上限强制结束并给出清晰提示
- **FR-011**: 每轮 LLM 响应与 Tool 结果都追加进 Session 对话历史，构成完整可查的调用链
- **FR-012**: Prompt 组装顺序固定：system prompt（`AGENT.md` 正文 + 当前日期时间）→ 对话历史（最近 `max_history_turns` 轮；一轮 = 一条用户消息及其后的全部 assistant/tool 消息，截断不切断工具调用链）→ 可用 Tool 列表（Function Calling 格式）
- **FR-013**: ToolExecutor 执行流程：按名查找 → 参数校验 → 执行 → 包装 ToolResult（`success`/`content`/`errorMessage`/`retryable`）→ 记录结构化日志
- **FR-014**: 提供内置 `http_get` Tool，可发起 HTTP GET 请求并将状态码与响应体返回给 LLM。成功语义：2xx → `success=true`；非 2xx → `success=false` 且 content 仍带状态码与响应体；4xx 及其他非 2xx（如 3xx）不重试、直接失败（`retryable=false`）；5xx 由工具内部自动重试（最多 3 次，间隔 500ms/1s/2s 线性退避），全部失败后 `success=false` + `retryable=true`
- **FR-015**: `http_get` 执行前必须过 Sandbox 白名单校验：定义 `Sandbox` 接口（接口先行、不携带实现细节），本里程碑实现 HTTP 域名白名单一档（`http.allowed_domains`），目标域名不在有效白名单时拒绝执行并返回失败 ToolResult；文件/Shell 两层校验随第二周补齐。白名单来源：全局 `.axion/config.yaml`（sandbox 段）+ Agent 级 `AGENT.md` frontmatter 声明；Agent 级一旦声明即完全替代全局列表（覆盖语义），Agent 级未声明时使用全局列表；两级均未配置时默认拒绝一切外网请求（fail-closed）

**工作区与 CLI**

- **FR-016**: `axion init` 幂等创建 `.axion/` 工作区（agents/skills/memory/logs 等目录 + AGENTS.md/SOUL.md/USER.md），已存在的内容一律不覆盖
- **FR-017**: `axion profile create/list/show/delete` 四个子命令操作 `.axion/agents/` 下的 Agent 目录
- **FR-018**: AgentLoader 扫描 `.axion/agents/`，把 `AGENT.md` frontmatter 派生为 Profile（name/description/identity/provider/tools/channels/settings）；启动时校验 provider 存在、tool 已注册、channel 支持；校验失败记错误日志但不阻断启动
- **FR-019**: `axion chat` 支持 `--profile <name>` 指定 Agent、`--message` 单条消息后退出、交互式多轮对话、`/quit` 退出
- **FR-020**: Session 为内存版：`session_id` 由 channel + user + profile 联合生成，进程内唯一；CLI Channel 下 channel 固定为 `cli`，user 取当前操作系统用户名（`user.name`），未来迁移为可配置方案（环境变量覆盖、缺省回落 OS 用户名）
- **FR-021**: 多个 Agent（Profile）可在同一实例并存，各自拥有独立配置与对话
- **FR-022**: `llm_calls` 与 `tool_invocations` 两张审计表本里程碑即写入 SQLite（day one 落库；不需要查询接口，写入不能省）：每次 LLM 调用与每次 Tool 调用（含失败）都落一条记录，字段与需求文档数据模型一致

### Key Entities

- **Profile**: Agent 的运行时配置对象，派生自 `AGENT.md` frontmatter。关键属性：`name`、`description`、`identity`（`agent_name`、`prompt`）、`provider`（`name`、`model`、`temperature`）、`tools` 列表、`channels` 列表、`settings`（`max_iterations`、`max_history_turns`）
- **Session**: 一次对话的上下文容器。关键属性：`session_id`（channel + user + profile 联合生成）、对话历史 messages 列表、`status`（active/archived）。本里程碑为内存态
- **Provider 映射**: provider name → LLM 连接（模型名、API key、可选 base URL）的显式映射表
- **AxionTool**: 统一 Tool 抽象。关键属性：`name`、`description`、`inputSchema`（JSON Schema）；行为：`execute(输入) → ToolResult`
- **ToolResult**: Tool 执行结果。关键属性：`success`、`content`、`errorMessage`、`retryable`
- **LlmCall / ToolInvocation 记录**: 每次 LLM 调用 / Tool 调用的审计记录（provider、model、tokens、耗时、参数、结果、成功与否）。本里程碑即写入 SQLite 审计表（day one）；Session 对话历史仍为内存态

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 在 JDK 21 且已配置 API key 环境变量的标准环境下，用户从执行 `axion init` 到在 chat 中得到第一句回复，全程不超过 5 分钟
- **SC-002**: Agent 在单次对话内自主完成"调用 `http_get` 获取外部数据并给出最终答案"的完整 ReAct 循环，用户全程零干预（第一周验收 Demo：天气查询）
- **SC-003**: ReAct 循环在三种终止条件下均正确结束——无 Tool 调用直接返回、Tool 链完成后返回、达到 `max_iterations` 强制结束——每种条件至少一个自动化用例覆盖
- **SC-004**: 抽测 10 组 ≥3 轮的多轮对话，Agent 对前文的引用正确：必须提供自动化测试用例（≥9/10 组通过，容忍 LLM 不确定性）；第一周 Demo 时进行人工抽测验收（人工判定目标 100%）
- **SC-005**: 除 LLM 调用外的单次消息处理内部开销 ≤ 50ms（不包含模型响应耗时）
- **SC-006**: 配置错误（缺 API key、provider 不存在、frontmatter 非法）100% 给出指明原因的清晰报错，0 静默失败
- **SC-007**: 两个声明不同 provider/模型的 Agent 在同一实例并存，各自对话按各自配置正确路由（显式映射验证）
- **SC-008**: 每次 LLM 调用与每次 Tool 调用（含失败）均产生一条 SQLite 审计记录，抽查任意一次对话可完整还原调用链

## Assumptions

- 9 模块 Maven 骨架已存在于当前仓库，本里程碑在其上填充核心运行时能力
- 目标用户是有基础命令行能力的企业开发者与运维人员
- 运行环境为 JDK 21+（Linux 主流发行版优先，桌面系统可用）
- 核心阶段先跑通一个 OpenAI 兼容协议 Provider（DeepSeek 或 Kimi），其余 Provider 在后续里程碑验证
- Session 对话历史持久化（跨重启恢复）由后续里程碑交付，本里程碑 Session 为内存态；审计两张表（`llm_calls` / `tool_invocations`）按宪法原则五在本里程碑即写入 SQLite
- 认证机制、SSE 流式、Tool 调用并行、Agent 间任务委托、Memory 体系、Web Service 均不在本里程碑范围，由后续 spec 覆盖；Sandbox 本里程碑仅交付接口 + HTTP 域名白名单一档，文件/Shell 两层校验随第二周补齐
- 术语沿用需求文档定义：Agent = 一个目录；Profile = frontmatter 派生的运行时配置；ReAct = Reason + Act；Channel = 消息接入入口
- CLI Channel 的 user 标识本里程碑取操作系统用户名（方案 A）；后续里程碑迁移为可配置方案（方案 C：环境变量覆盖、缺省回落 OS 用户名），因 C 的回落默认值即 A 的行为，迁移对存量会话与审计记录无影响
