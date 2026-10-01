# Data Model: ReAct Runtime（第一周）

来源：spec.md Key Entities + Clarifications（Session 2026-08-24）。本里程碑 Session 为内存态，仅审计两表落 SQLite。

## 1. Profile（运行时配置对象）

派生自 `.axion/agents/<name>/AGENT.md` frontmatter（`AgentLoader.deriveProfile`）。

| 字段 | 类型 | 必填 | 说明 / 校验规则 |
|------|------|------|----------------|
| `name` | String | ✅ | 目录名，Agent 唯一标识；缺失 → 该 Agent 不可用（记错误日志） |
| `description` | String | ❌ | 展示用途 |
| `identity.agent_name` | String | ❌ | 显示名 |
| `identity.prompt`（正文） | String | ✅ | 任务指令，注入 system prompt；正文为空 → 该 Agent 不可用 |
| `provider.name` | String | ✅ | 必须存在于 ProviderService 显式映射；否则启动校验失败（不阻断其他 Agent） |
| `provider.model` | String | ✅ | 模型名 |
| `provider.temperature` | Double | ❌ | 默认 0.7 |
| `tools` | List<String> | ❌ | 每个条目必须已注册于 ToolRegistry；未注册 → 校验失败 |
| `channels` | List<String> | ❌ | 每个条目必须是已支持 Channel；本里程碑仅 `cli` |
| `sandbox.allowed_domains` | List<String> | ❌ | **Clarification Q2**：一旦声明即完全替代全局列表（覆盖语义）；未声明使用全局 |
| `settings.max_iterations` | int | ❌ | 默认 10 |
| `settings.max_history_turns` | int | ❌ | 默认 20 |

**校验失败处理（FR-018）**：记错误日志，该 Agent 标记不可用，不阻断其他 Agent。

## 2. Session（内存态）

| 字段 | 类型 | 说明 |
|------|------|------|
| `session_id` | String | `channel + user + profile` 联合生成。**Clarification Q3**：CLI 下 channel=`cli`，user=OS 用户名（`user.name`）；后续里程碑迁移可配置方案（env 覆盖 + 回落 OS 用户名） |
| `messages` | List<Message> | 按序追加的完整对话链（含 tool 消息） |
| `status` | enum(active/archived) | 本里程碑仅 active 使用，字段为第四周持久化预留 |
| `createdAt` / `lastActiveAt` | Instant | 进程内 |

### 截断规则（Clarification Q1）

`max_history_turns` 的"一轮" = 一条用户消息及其后的**全部** assistant/tool 消息。组装 prompt 时保留最近 N 条用户消息开始的完整链，绝不从工具调用链中间截断；system prompt 永远保留。

### Message（核心内部表示，channel 无关）

| 字段 | 类型 | 说明 |
|------|------|------|
| `role` | enum(system/user/assistant/tool) | |
| `content` | String | |
| `toolCalls` | List<ToolCall> | assistant 消息携带（id/name/arguments JSON） |
| `toolCallId` | String | tool 角色消息携带，回填对应调用 |

PromptBuilder 负责转换为 Spring AI 消息类型（仅 provider 调用层接触 Spring AI 类型）。

## 3. Provider 映射（显式）

`Map<String, ProviderSpec>`；`ProviderSpec` = {`name`, `baseUrl`（可选，OpenAI 兼容端点）, `apiKeyEnvVar`（`${VAR}` 占位，加载时解析）, 默认 `model`}。ProviderService 按 `provider.name` 取 ChatModel 实例（宪法原则三：不扫描容器 Bean 类型）。本里程碑注册 DeepSeek 或 Kimi 至少一个（FR-006）。

## 4. AxionTool / ToolResult（统一 Tool 抽象，core 定义）

| AxionTool | 说明 |
|----------|------|
| `getName(): String` | Tool 名（Function Calling 中的 name） |
| `getDescription(): String` | 描述（注入 Tool 列表） |
| `getInputSchema(): JsonSchema` | 入参 JSON Schema |
| `execute(JsonNode input): ToolResult` | 执行；内部异常必须捕获转为失败 ToolResult |

| ToolResult | 说明 |
|------------|------|
| `success: boolean` | **Clarification Q4**：`http_get` 语义 = 2xx→true；非 2xx→false（content 仍带状态码+响应体） |
| `content: String` | 结果内容（失败时同样回填，Agent 不损失信息） |
| `errorMessage: String` | 可空 |
| `retryable: boolean` | 4xx/其他非 2xx→false；5xx（内部重试耗尽后）→true |

## 5. 审计实体（SQLite，day one 落库）

### llm_calls

| 字段 | SQLite 类型 | 说明 |
|------|------------|------|
| `id` | INTEGER PK | 自增 |
| `session_id` | VARCHAR | 关联 Session（内存 Session 无表，仅字符串外键） |
| `provider` | VARCHAR | Provider 名 |
| `model` | VARCHAR | 模型名 |
| `prompt_tokens` | INT | |
| `completion_tokens` | INT | |
| `total_tokens` | INT | |
| `duration_ms` | BIGINT | 调用耗时 |
| `created_at` | TEXT | ISO-8601 UTC（SQLite 无原生 TIMESTAMP；JPA `Instant` + AttributeConverter） |

### tool_invocations

| 字段 | SQLite 类型 | 说明 |
|------|------------|------|
| `id` | INTEGER PK | 自增 |
| `session_id` | VARCHAR | |
| `tool_name` | VARCHAR | |
| `input_json` | TEXT | 调用参数 JSON |
| `result_json` | TEXT | 执行结果（含成功/失败） |
| `success` | BOOLEAN | 语义同 ToolResult.success |
| `error_message` | TEXT | 可空 |
| `duration_ms` | BIGINT | |
| `created_at` | TEXT | ISO-8601 UTC |

**写入规则（FR-022 + Edge Cases）**：
- 每次 LLM 调用、每次 Tool 调用（含失败）各落一条；Tool 内部 5xx 重试属同一次调用，仍只记一条（重试次数可记入 result_json/error_message）
- 审计写入失败：记错误日志，不阻断对话主流程
- 建表走手动 `schema.sql`（`CREATE TABLE IF NOT EXISTS`，幂等），不依赖 `hibernate.ddl-auto`

## 6. 全局配置（`.axion/config.yaml`，Clarification Q2 引入）

```yaml
sandbox:
  http:
    allowed_domains:   # 全局白名单；缺省或空 = 未配置
      - "*.example.com"
```

- 有效白名单 = Agent 级声明 ? Agent 级列表 : 全局列表（覆盖语义，非并集）
- 两级均未配置 → fail-closed：拒绝一切外网请求
- 域名匹配支持通配符 `*`（如 `*.example.com`）；目标 URL 的 host 精确/通配匹配，不匹配即拒绝并返回失败 ToolResult
