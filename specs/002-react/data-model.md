# Data Model: 002-react

## Session（会话，最小契约）

core 新增。对话历史累积容器，内存版（sessions 表与 JPA 持久化归第 18 节）。

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | String | 会话标识 = channel + user + profileName 联合拼接（**只在 SessionManager 内拼接**，H4 不变量四） |
| `profileName` | String | 关联 Profile 名 |
| `channel` | String | 接入渠道 |
| `userId` | String | 用户标识 |
| `messages` | List\<Message> | 累积的对话历史（`Message` 为 001 已交付类型：role/content/toolCalls/toolResults，框架无关、可 JSON 序列化） |

行为：`append(Message)` 追加累积（坑三）；截断只在 PromptBuilder 组装时按轮进行，不改 Session 本体。

## ToolInvocation（工具调用审计记录，新表 `tool_invocations`）

axion-storage 新增 JPA 实体。手工 schema.sql 增量建表（与 001 `llm_calls` 同口径）。

| 字段 | 列 | 类型 | 说明 |
|------|------|------|------|
| `id` | `id` | BIGINT PK 自增 | 主键 |
| `sessionId` | `session_id` | VARCHAR | 关联会话 |
| `toolName` | `tool_name` | VARCHAR | 工具名 |
| `inputJson` | `input_json` | TEXT | 调用参数（JSON 序列化） |
| `resultJson` | `result_json` | TEXT | 执行结果（JSON 序列化；失败时记错误信息上下文） |
| `success` | `success` | BOOLEAN | 成功与否——**成功失败都落账**（宪法 V） |
| `errorMessage` | `error_message` | TEXT 可空 | 失败原因 |
| `durationMs` | `duration_ms` | BIGINT | 执行耗时 |
| `createdAt` | `created_at` | TEXT（ISO-8601） | 调用时间（复用 001 `InstantTextConverter`，SQLite 无原生 TIMESTAMP） |

## SandboxAction / ActionType（沙箱校验请求，axion-tool）

纯接口墙（本节零实现、无人调用；接线从第 20 节起）。

| 类型 | 形态 | 说明 |
|------|------|------|
| `ActionType` | enum：`FILE_READ` \| `FILE_WRITE` \| `SHELL_COMMAND` \| `HTTP_REQUEST` | 四种涉外动作（文件读/文件写/Shell 命令/HTTP 请求） |
| `SandboxAction` | record：`type` + `target`（String） | 一个待校验动作（类型 + 目标：路径/命令/URL） |
| `SandboxViolationException` | RuntimeException | 校验失败抛出，信息说明被拒动作 |

## Prompt（每轮组装产物）

`PromptBuilder` 组装，非持久化。四部分（需求文档 FR-2）：

| 段 | 内容 | 来源 |
|------|------|------|
| ① system | `Profile.identity.prompt`（角色设定）+ Bootstrap 文件内容 + 已绑定 Skill 元数据（name/description/读取路径），**末尾附当前日期时间** | ContextLoader（每次现读） |
| ② 长期记忆 | 跨会话记忆——**Memory 模块未就绪（第 21/22 节），本节跳过留拼接位** | MemoryService（后续节） |
| ③ 对话历史 | 最近 N 轮（默认 20，超出截断；TOOL 消息跟随所属响应成组保留，不切断一轮内 tool 调用链） | Session.messages |
| ④ 工具列表 | Function Calling 格式（AxionTool 集合 → 工具定义；注入的工具集，ToolRegistry 归第 20 节） | ToolSchemaAdapter 契约（落位见 research.md R3 拍板） |

## ProfileContext（当前 Agent 上下文，core）

ThreadLocal\<Profile>（虚拟线程每请求独立）。`AgentService.process` 设置、`finally` 清理（坑四）；工具执行时经它读取当前 Agent 配置（`AxionTool.execute` 签名不带 Profile，不改工具接口）。

## 关系图

```
Profile（001）
  └─1:N─ Session（本节，内存版）
           └─1:N─ Message（001，累积历史）
  └─运行配置─ ReActLoop 每轮调 ProviderService（001，llm_calls 审计）
Session ─1:N─ ToolInvocation（本节，tool_invocations 审计）
AxionTool（001）─执行─ ToolResult（001）─回填─ Session
SandboxAction（本节，axion-tool）─校验─ 各工具 execute 首行（第 20 节起接线）
```
