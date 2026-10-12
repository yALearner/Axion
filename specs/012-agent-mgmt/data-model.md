# Data Model: 012-agent-mgmt

> 无新数据库表（复用 sessions/llm_calls/tool_invocations；Agent 目录与归档落文件系统）。本节数据面 = DTO + 错误码 + 配置键 + 目录布局 + 生命周期状态机。

## 一、DTO（axion-web，Jackson 序列化，全部走 ApiResponse 信封）

| 类型 | 字段 | 说明 |
|------|------|------|
| `CreateAgentRequest` | `name: String`（必填，=目录名，坑八同款校验）、`description: String`（必填） | 创建请求：只收 name+description，脚手架由后端模板产出（5.1 拍板） |
| `UpdateAgentRequest` | `agentMd: String` | 整份 AGENT.md 覆写（语义最简，与 PUT 语义和校验路径一致）；schedules 变更先注销旧句柄再注册新（FR-005） |
| `AgentView` | `name`、`description`、`provider`、`model`、`tools`、`schedules`（含 id/cron/zone/message）、`createdAt?` | 列表/详情展示（派生自 Profile + AGENT.md）；不对前端暴露 api_key |
| `GenerateFilesRequest` | `description: String` | 按描述重生成（5.2.4） |
| `GenerateFilesResponse` | `files: Map<String, String>`（相对路径 → 内容） | 草稿预览（不落盘不注册）；相对路径以 `agents/<name>/` 为根（如 `AGENT.md`、`scripts/x.py`） |
| `SaveFilesRequest` | `files: Map<String, String>` | 保存一组文件（可能被用户改过）；先校验 AGENT.md 可解析再写盘 |
| `FileNode` | `name`、`path`、`type`（dir/file）、`children: List<FileNode>`（目录时） | 工作区目录树节点 |
| `WorkspaceFileRequest` | `path: String`、`content: String` | 写文件请求（5.2.3）；防目录穿越（坑十二） |
| `SessionView` | `sessionId`、`profileName`、`messages: List<MessageView>`（最近 ≤100 条） | 固定会话视图（5.2.2）；message 字段沿用 009 既有 MessageView 口径 |
| `SessionMessageRequest` | `content: String` | 向固定会话发消息（触发 ReAct） |

## 二、错误码新增（ErrorCode 枚举，web）

| code | HTTP | 触发场景 |
|------|------|---------|
| `AGENT_EXISTS` | 400 | name 已存在（坑十八：第一步就拒） |
| `AGENT_NOT_FOUND` | 404 | GET/PUT/DELETE/会话/记忆 目标不存在 |
| `AGENT_INVALID` | 400 | AGENT.md 字段非法/解析失败（含 name=目录名不一致、provider 不存在） |
| `GENERATE_INVALID` | 400 | generate-files 产出无法解析（坑十四，带可读原因） |
| `AUTHOR_MODEL_MISSING` | 503 | axion.author.model 未配置（坑十五，不发 model=null） |

> 既有 code 复用：目录穿越 → `INVALID_REQUEST` 类既有 code（400）；其余沿用 009 口径。

## 三、配置键新增（axion-boot application.yaml）

```yaml
axion:
  author:
    provider: deepseek     # 生成用 provider；缺省取 axion.providers 第一个
    model: deepseek-chat   # 留空 → 生成端点 503（坑十五）
```

## 四、目录布局（文件系统真相源）

```
.axion/
├── agents/<name>/           # 一个目录 = 一个 Agent
│   ├── AGENT.md             # frontmatter（Profile）+ 正文（指令）
│   ├── MEMORY.md            # per-agent 专属记忆（5.2.1；Markdown 档运行时解析）
│   ├── scripts/             # 脚手架空目录占位（模板产出）
│   ├── skills/              # 软连接绑定视图（模板空目录）
│   └── REFERENCE.md         # 脚手架字段说明模板
├── archive/<name>/          # 删除归档（不物理删，可追溯）
├── memory/MEMORY.md         # 全局记忆（无 Agent 上下文时回退；不主动删除）
└── axion.db                 # SQLite（无新表）
```

## 五、Agent 生命周期（L4 状态机）

| 状态 | 进入条件 | 离开条件 |
|------|---------|---------|
| 不存在 | 初始 | create（API）/ 目录拷入（watcher）→ 已注册 |
| 已注册 | register(agentDir) 成功（ProfileRegistry + 可选 schedules 句柄） | 目录修改 → 重注册（同状态更新）；删除 → 已归档 |
| 已归档 | delete（注销定时 → 移出索引 → 目录移入 archive/） | 无自动恢复（手工从 archive 移回 = 手工丢目录路径重新注册） |
| （坏目录） | 解析失败：WARN 跳过、不注册（无状态进入，仅日志可见——011 债③ 盲区延续） | 修复后由 watcher 事件重试拾取 |

**不变量**：
1. 三条录入（API/丢目录/启动扫描）对同一目录只能有同一注册结果（同一段代码，坑十七）
2. 删除顺序：注销定时 → 移出索引 → 归档（坑十，顺序钉死）
3. 创建失败：已写目录回滚删除（坑十一，不留半个 Agent）
4. 记忆上下文：置入后 finally 必清（坑十六）；无上下文回退全局
5. 会话：admin:console:<name> 恒为同一条（getOrCreate 幂等；session_id 拼接只在 SessionManager）
