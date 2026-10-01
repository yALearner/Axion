# Data Model: 003-cli

## SessionEntity（会话存档行，新表 `sessions`）

axion-storage 新增 JPA 实体。手工 schema.sql 增量建表（坑八口径，与 llm_calls/tool_invocations 同源）。

| 字段 | 列 | 类型 | 说明 |
|------|------|------|------|
| `sessionId` | `session_id` | TEXT PK | 会话标识 = channel\|user\|profileName 联合拼接（**拼接只在 SessionManager 一处**，H4 不变量四） |
| `profileName` | `profile_name` | TEXT | 关联 Profile 名 |
| `channel` | `channel` | TEXT | 接入渠道（CLI 传 "cli"） |
| `userId` | `user_id` | TEXT | 用户标识（CLI 取本机用户名） |
| `messagesJson` | `messages_json` | TEXT | 对话历史整体 JSON 序列化（含 toolCall/toolResult 嵌套，一列存不拆表） |
| `status` | `status` | TEXT | `active` / `archived`（本节只写 active；归档流转归第 26 节） |
| `createdAt` | `created_at` | TEXT（ISO-8601） | 创建时间（复用 `InstantTextConverter`） |
| `lastActiveAt` | `last_active_at` | TEXT（ISO-8601） | 最后活跃时间（save 时更新） |
| `archivedAt` | `archived_at` | TEXT（ISO-8601，可空） | 归档时间（第 26 节起使用） |

## Session（会话，core，002 已交付——本节不动）

内存消息容器：id/profileName/channel/userId/messages（List\<Message>）。持久化行由 SessionEntity 承担，两者转换收口在 SessionManager（私有方法）。

## 会话生命周期（本节范围）

```
新建（getOrCreate 未命中）──status=active──→ 活跃
活跃 ──save──→ 落库（messages_json 刷新 + last_active_at 更新）
活跃 ──进程重启──→ 查库命中 ──→ 反序列化重建（跨重启恢复）
活跃 ──归档──→ archived（归第 26 节 Web 的 DELETE /sessions/{id}，本节不做）
```

## CLI 命令清单（12 个 + 轻重分类）

| 命令 | 分类 | 数据源 | 输出 |
|------|------|--------|------|
| init | 轻 | 文件系统 | 建 .axion/ 目录树（幂等） |
| profile list | 轻 | `.axion/agents/` 目录 | Agent 名列表 |
| profile create \<name> | 轻 | 文件系统 | 生成 agents/\<name>/AGENT.md 模板 |
| profile show \<name> | 轻 | AGENT.md frontmatter | 配置概要（不含 api-key 值） |
| profile delete \<name> | 轻 | 文件系统 | 删除目录 + 输出路径 |
| status | 轻 | 文件系统 + JDBC 计数 | 工作区/Agent 数/会话数/库位置 |
| provider list | 轻 | classpath application.yaml | name/model/base-url（**api-key 永不输出**） |
| tool list | 轻 | — | 占位提示（第 20 节） |
| session list | 轻 | JDBC 直读 sessions 表 | 会话概要（id/profile/channel/最后活跃） |
| chat | 重 | Spring 上下文 | 交互对话 / --message 单条 |
| serve | 重 | Spring 上下文 | 占位提示（Web 归第 26 节） |
| gateway | 重 | Spring 上下文 | 占位提示（多通道归后续节） |

## 关键不变量

1. **session_id 拼接只发生在 SessionManager 一处**（H4 不变量四；架构断言 + 包私有构造器钉死）
2. **轻命令零 Spring 类加载**（人工清单验证：无 Spring 启动日志）
3. **坑九**：重命令启动类显式声明 JPA 扫描根（架构断言钉死；Found N > 0 人工核验）
4. **api-key 零明文输出**（provider list 掩码打印；配置仍走 ${ENV} 占位）
5. **审计双表延续**：chat 过程 llm_calls/tool_invocations 由 002 引擎继续落库，本节不新增旁路
