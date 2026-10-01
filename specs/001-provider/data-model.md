# Data Model: 001-provider

## Provider 声明（全局配置）

`application.yaml` 的 `axion.providers` 列表项。名字是全局唯一标识。

| 字段 | 类型 | 说明 | 校验 |
|------|------|------|------|
| `name` | String | Provider 唯一名（如 `deepseek`） | 必填、全局唯一；Profile 引用必须命中 |
| `api-key` | String | 凭证来源 | 只允许 `${ENV_VAR}` 占位，禁止明文 |
| `base-url` | String | 可选接入地址 | 可空 |
| `model`（默认项） | String | 默认模型名 | 以实际 starter 支持为准 |

## Profile（Agent 运行时配置）

派生自 `AGENT.md` frontmatter（`AgentLoader.deriveProfile`，本节基础版）。类本身一次建全，后续节取用。

| 字段 | 类型 | 说明 |
|------|------|------|
| `name` | String | Agent 名，内存索引键 |
| `description` | String | 描述 |
| `identity.agent_name` | String | 展示名 |
| `identity.prompt` | String | 任务指令正文 |
| `provider.name` | String | 引用全局层 Provider 名——**必须命中，否则报错** |
| `provider.model` | String | 模型名 |
| `provider.temperature` | Double | 温度 |
| `tools` | List<String> | 可用工具名列表 |
| `mcp_servers` | List<String> | MCP server 列表 |
| `channels` | List<String> | 渠道列表 |
| `schedules` | List<Schedule> | 定时计划（cron/zone/message） |
| `bootstrap` | List<String> | 引导文件名 |
| `settings.max_iterations` | Integer | 循环上限（默认 10） |
| `settings.max_history_turns` | Integer | 历史轮数（默认 20） |

**校验规则（本节范围）**：`provider.name` 必须存在于全局层（本节唯一校验项，其余字段校验归后续各节）；坏的 Profile 记错误日志、不阻断启动，不注册进索引。

## Message（对话消息）

| 字段 | 说明 |
|------|------|
| `role` | system / user / assistant / tool |
| `content` | 文本内容 |
| `toolCalls` | 模型请求的工具调用（透传用，不执行） |
| `toolResults` | 工具执行结果（后续节回填） |

## AxionTool（工具抽象，本节只交付接口与 schema）

| 字段 | 说明 |
|------|------|
| `name` | 工具名 |
| `description` | 用途说明 |
| `inputSchema` | 参数 JSON Schema（翻译为 Spring AI 工具格式的来源） |

## LlmCall（llm_calls 表，审计 day one）

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | BIGINT PK | 主键 |
| `session_id` | VARCHAR | 关联会话（Session 实体归后续节，本节以调用方传入的 id 关联） |
| `provider` | VARCHAR | Provider 名 |
| `model` | VARCHAR | 模型名 |
| `prompt_tokens` | INT | 输入 token |
| `completion_tokens` | INT | 输出 token |
| `total_tokens` | INT | 总 token |
| `success` | BOOLEAN | 是否成功（补充修订列） |
| `error_message` | TEXT | 失败原因（可空，补充修订列） |
| `duration_ms` | BIGINT | 耗时 |
| `created_at` | TEXT | ISO-8601（SQLite 无原生 TIMESTAMP） |

**不变量**：成功与失败路径都必须产生一行；失败时 `success=false` + `error_message` 有值，且异常继续上抛。

## 关系

- `Profile.provider.name` →→ `Provider.name`（引用关系，校验强制存在）
- `LlmCall.session_id` →→ Session（跨节引用，Session 实体后续节交付）
- `ProviderService.chat(sessionId, Profile, Prompt)` → 产生 ≤1 行 `LlmCall`
