# Contracts: 012-agent-mgmt 端点契约与跨节契约

> 端点全集 = 课件 5.3 全表。统一 `ApiResponse` 信封（009）；错误码见 data-model.md §二。全部同步阻塞（宪法 VII）。

## 一、端点契约（12 个）

| 方法 | 路径 | 请求 | 响应（data） | 错误 |
|------|------|------|-------------|------|
| POST | `/api/v1/agents` | `{name, description}` | `AgentView` | 400 `AGENT_EXISTS`（第一步就拒）/ 400 `AGENT_INVALID`；失败回滚不留半个 Agent |
| GET | `/api/v1/agents` | — | `List<AgentView>` | — |
| GET | `/api/v1/agents/{name}` | — | `AgentView` | 404 `AGENT_NOT_FOUND` |
| PUT | `/api/v1/agents/{name}` | `UpdateAgentRequest`（整份 AGENT.md 覆写） | `AgentView` | 404 / 400 `AGENT_INVALID`；schedules 变更先注销旧句柄再注册新 |
| DELETE | `/api/v1/agents/{name}` | — | `null` | 404；顺序：注销定时 → 移出索引 → 归档 `.axion/archive/` |
| POST | `/api/v1/agents/{name}/invoke` | 009 既有形态（无状态调用） | 既有 | 不变（26 节交付，零改动） |
| GET | `/api/v1/agents/{name}/memory` | — | 记忆内容（String） | 404 |
| GET | `/api/v1/agents/{name}/session` | — | `SessionView`（sessionId + profileName + 最近 ≤100 条） | 404 |
| POST | `/api/v1/agents/{name}/session/messages` | `{content}` | 最终回复 | 404；触发 ReAct（`AgentService.process`，宪法 VIII） |
| POST | `/api/v1/agents/{name}/generate-files` | `{description}` | `GenerateFilesResponse`（相对路径→内容） | 404 / 400 `GENERATE_INVALID` / 503 `AUTHOR_MODEL_MISSING`；不落盘不注册；落 llm_calls 审计 |
| POST | `/api/v1/agents/{name}/files` | `{files}` | `AgentView` | 404 / 400 `AGENT_INVALID`（先校验再写盘）；写入即生效（重注册） |
| GET | `/api/v1/workspace/tree` | — | `FileNode`（agents/ 可展开 + archive/） | — |
| GET | `/api/v1/workspace/file?path=` | query path | 文件内容（String） | 400（穿越/不存在） |
| POST | `/api/v1/workspace/file` | `{path, content}` | `null` | 400（穿越）；`agents/<name>/AGENT.md` 走 `AgentLifecycleService.update` 重注册（坑十三），其余直接写盘 |

> 注：5.3 全表共（POST /agents、GET /agents、GET /agents/{name}、PUT、DELETE、invoke、memory、session、session/messages、generate-files、files、workspace/tree、workspace/file 读、workspace/file 写——14 个方法条目，其中 workspace 三法归 WorkspaceApiController，AgentApiController 十一法（含既有 invoke）；本表为完整方法清单）。

## 二、退役端点（009 改造点）

- **移除** `GET /api/v1/memory`（全局记忆查询）——由 `GET /api/v1/agents/{name}/memory` 取代（课件 5.2.1 明示移除，不留双路径）。管理台「长期记忆」页随之下线。

## 三、运行时行为不变量（harness 钉死）

1. **一段注册代码（坑十七）**：`AgentLifecycleService.register(agentDir)` 是 API 上传、WorkspaceWatcher 事件、启动扫描三条录入的唯一注册入口；同一套校验、同一异常、同一消息。
2. **删除顺序（坑十）**：`unregisterProfile`（cancel(false) 句柄 + 移除 registrations，不动 taskLocks）→ `ProfileRegistry.remove` → `agentStore.archive`；InOrder 断言。
3. **创建回滚（坑十一）**：注册失败 → 已写目录删除；定时从未注册（never() 断言）。
4. **防目录穿越（坑十二）**：`normalize()` 后 `startsWith(workspaceRoot)`，否则 400。
5. **生成不落盘（5.2.4）**：generate-files 只返回草稿；磁盘与注册状态零变化（断言）。
6. **记忆上下文（坑十六）**：置入/清除成对出现（finally）；无上下文回退全局路径；SPI 契约零改动（006 契约测试原样回归）。
7. **固定会话**：`admin:console:<name>` getOrCreate 幂等；session_id 拼接只在 SessionManager（不变量④）。

## 四、跨节契约（后续节不得改动已验收行为）

- **31 节**（三个真实 Agent 演示：天气/科技日报/GitHub 日报）：全部经本节三路径之一上线（管理台新建 / POST /agents / 丢目录）；详情 5 tab 是演示期间的管理操作面。31 节改动本节端点/顺序视为修改前序公共接口。
- **32 节**（打包发布）：`axion.author.*` 配置键、管理台 Agent 页是发布形态的组成部分。
- **审计口径**：generate-files 落 llm_calls（session_id 关联生成会话）；create/update/delete 是配置变更不落审计双表；会话消息与定时触发照常落双表（宪法 V）。
- **Watcher 生命周期**：守护线程随 CliAgentConfiguration 装配常驻（chat/serve/gateway 三模式一致）。
