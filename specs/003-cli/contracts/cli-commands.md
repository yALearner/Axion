# 接口契约：CLI 12 子命令

> **跨节契约**：第 25 节（钟推）复用同一 `AgentService` 链路与 session 三元组公式；第 26 节（Web）复用 SessionRepository 与 sessions 表口径；第 27/28 节全流程串联按本命令形态操作。修改本契约视为修改公共接口，必须停下报告。

## 命令总表

| 命令 | 参数 | 轻重 | 退出码语义 |
|------|------|------|-----------|
| `init` | 无 | 轻 | 0 成功（幂等，已存在不覆盖） |
| `profile list` | 无 | 轻 | 0；无 Agent 时输出空列表 |
| `profile create` | `<name>` 必填 | 轻 | 0 成功；目录已存在时提示不覆盖 |
| `profile show` | `<name>` 必填 | 轻 | 0；不存在时清晰报错非零退出 |
| `profile delete` | `<name>` 必填 | 轻 | 0；不存在时清晰报错非零退出 |
| `status` | 无 | 轻 | 0；未初始化时提示先 init |
| `provider list` | 无 | 轻 | 0；api-key 永不输出 |
| `tool list` | 无 | 轻 | 0；输出占位提示（第 20 节） |
| `session list` | 无 | 轻 | 0；无库/无表时提示先 init |
| `chat` | `--profile`（默认 default）、`--message`（可选） | 重 | 0 正常退出；/quit 退出；Profile 未注册清晰报错非零 |
| `serve` | 无 | 重 | 0；输出占位提示（Web 归第 26 节） |
| `gateway` | 无 | 重 | 0；输出占位提示（多通道归后续节） |

## 行为不变量

1. **轻重分流**：轻命令零 Spring 启动（无 Spring 日志）；重命令统一以 `AxionApplication` 启动（坑九：JPA 扫描根已显式声明，Found N > 0）
2. **三元组纪律**：CLI 只提供三元组 (channel="cli", user=本机用户名, profileName)，session_id 拼接只在 SessionManager 一处——任何命令不得自行拼字符串（H4 不变量四）
3. **chat 薄壳**：读输入 → `AgentService.process(session, line)` → 打印；唯一自判断逻辑是 `/quit`；`--message` 单条处理后退
4. **api-key 零泄漏**：所有查询输出不含凭证（provider list 只打 name/model/base-url）
5. **错误不静默**：未初始化/未注册/文件缺失 → 清晰报错 + 非零退出码
6. **同步阻塞**：chat 全程同步（宪法 VII），虚拟线程承载并发

## 消费方

- 当前：终端用户（本节验收场景）
- 后续：第 25 节 AgentScheduler（同一 AgentService 链路 + 钟推 session 三元组公式）、第 27/28 节 Demo 全流程操作脚本
