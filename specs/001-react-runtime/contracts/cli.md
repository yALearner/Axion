# CLI 契约（第一周）

入口：`axion`（Picocli）。通用约定：正常输出走 stdout；错误与校验失败走 stderr 并**指明具体原因**（缺哪个环境变量、哪个 provider 不存在等，SC-006：0 静默失败）；退出码 `0`=成功，`1`=运行时/配置错误，`2`=用法错误（Picocli 默认）。

## 1. `axion init`

```text
Usage: axion init
```

- 幂等创建 `.axion/` 工作区：`agents/`、`skills/`、`memory/`、`logs/` 目录 + `AGENTS.md`、`SOUL.md`、`USER.md`（FR-016）
- 已存在的内容一律不覆盖；重复执行返回成功并提示已初始化
- 输出：创建结果摘要（哪些目录/文件已创建或已存在）

## 2. `axion chat`

```text
Usage: axion chat [--profile <name>] [--message <text>]
```

| 形态 | 行为 |
|------|------|
| `axion chat --profile <name>` | 进入交互式多轮对话（提示符输入；`/quit` 退出，exit 0） |
| `axion chat --profile <name> --message "..."` | 发单条消息 → 输出回复 → 进程退出（exit 0） |
| 未指定 `--profile` | 若工作区恰好 1 个 Agent：默认使用并提示；0 个或多个：报错并**列出可用 Agent**（Edge Cases） |
| 空消息 | 不触发 LLM 调用，友好提示后继续等待输入 |

- 交互轮次累积进 Session（内存态，进程结束不恢复）；超出 `max_history_turns` 按截断规则组装（见 data-model.md §2）
- 单条消息处理内部开销 ≤ 50ms（SC-005，不含 LLM 响应）
- LLM 调用失败：stderr 明确失败信息，进程不崩溃，可继续下一条输入

## 3. `axion profile`（操作 `.axion/agents/` 下 Agent 目录）

```text
Usage:
  axion profile list
  axion profile create <name>
  axion profile show <name>
  axion profile delete <name>
```

- `create`：生成 `.axion/agents/<name>/AGENT.md` 最小模板（frontmatter + 正文）；同名已存在 → 报错不覆盖
- `list`：表格输出 name / description / provider / tools（frontmatter 非法目录以"不可用"状态标出，不中断列表）
- `show`：打印该 Agent 的 AGENT.md 内容（派生后的 Profile 视图）
- `delete`：删除目录；不存在 → 报错（exit 1）

## 4. 查询命令

```text
Usage:
  axion provider list     # 已配置 Provider：name / model / baseUrl（脱敏，不输出 API key）
  axion tool list         # ToolRegistry 已注册 Tool：name / description 摘要
```

## 5. 退出码汇总

| 场景 | exit |
|------|------|
| 正常完成 | 0 |
| 配置缺失/非法（缺 API key、provider 不存在、frontmatter 非法） | 1 + 指明具体变量的 stderr |
| LLM 调用失败（网络/配额） | 1 + 明确失败信息（`--message` 形态）；交互形态不退出，提示后继续 |
| 用法错误（未知参数/命令） | 2（Picocli usage） |

## 6. 可观察性约定

- 用户对话输出：仅 Agent 最终回复，本里程碑**默认不展示**中间 Tool 调用过程（决策已定）；polish 阶段可加 `--verbose` 开关再议
- 结构化 JSON 日志落 `.axion/logs/`（实现细节见 research.md）
- 审计两表写入 SQLite（`.axion/axion.db`），无查询接口（FR-022）
