# Data Model: 004-notify

## notify_channels（通知渠道全局注册表，新表，SQLite 第 4 张）

axion-storage 新增。手工 schema.sql 增量建表（坑八口径：不依赖 `ddl-auto=update`，测试执行生产同一份脚本）。

| 列 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `name` | TEXT | PRIMARY KEY | 注册名：Agent 正文按此名引用渠道（LLM 传的 `channel` 参数值） |
| `type` | TEXT | NOT NULL | 渠道类型（核心阶段均为 `webhook`；扩展阶段其他类型自行解释 `url` 语义） |
| `url` | TEXT | NOT NULL | webhook 地址；不进对话、不进日志参数、不进 frontmatter |
| `description` | TEXT | 可空 | 可选描述（人工可读备注） |

```sql
CREATE TABLE IF NOT EXISTS notify_channels (
    name        TEXT PRIMARY KEY,
    type        TEXT NOT NULL,
    url         TEXT NOT NULL,
    description TEXT
);
```

- **唯一性规则**：`name` 主键唯一——重复注册同一渠道名被拒绝（单测回归）。
- **生命周期**：无状态机——纯配置数据，CRUD 归 Web Service 节（本节只交表 + 仓储 + 解析）；本节无写路径。
- **实体/仓储**：`NotifyChannelEntity` + `NotifyChannelRepository extends JpaRepository<NotifyChannelEntity, String>`（按 name 主键查 = findById，storage 模式机械延伸；沿用 storage flat 包与既有风格，无 ISO 时间列故不涉 `InstantTextConverter`）。

## NotifyTarget（解析结果，axion-tool，非持久化）

| 字段 | 类型 | 说明 |
|------|------|------|
| `channelType` | String | = 表 `type` 列；adapter 显式 Map 的选键 |
| `config` | Map\<String, String> | 含 `url`（= 表 `url` 列）与 `name`（= 表 `name` 列，供审计结果带渠道名）；其他键由实现类自行解释 |

- 接口层不携带任何实现细节——具体是 webhook 地址还是认证信息由实现类解释（FR-2）。
- 由 `NotifyChannelRegistry` 解析产生；`NotifyTools` 不再读表。

## 解析口径（NotifyChannelRegistry，纯数据）

| 输入 | 行为 |
|------|------|
| `channel` 显式指定、注册表命中 | 返回对应 NotifyTarget |
| `channel` 显式指定、未命中 | 明确报错（不静默；审计落 success=false） |
| `channel` 缺省、注册表恰好一条 | 取这条唯一渠道 |
| `channel` 缺省、注册表多条或为空 | 明确报错，要求显式指定 channel（拍板口径） |

## 审计口径（复用，不新增）

`tool_invocations` 既有列：notify 成功 → `success=true`、`result_json` 内容为 **"已推送到 <渠道名>"**（带渠道名，可查出推给了谁，不裸记"已推送"）；失败（解析失败/未知 type/enforce 拒绝/send 异常）→ `success=false` + `error_message`，`retryable` 沿用 ToolExecutor 统一口径（4xx/5xx 不细分，留白）。

## 涉及的其他既有实体

- **AxionTool / ToolResult**（core，001 交付）：NotifyTools 实现与返回值，原样使用
- **Sandbox / SandboxAction / ActionType / SandboxViolationException**（tool，002 交付）：`enforce(new SandboxAction(HTTP_REQUEST, url))`，原样使用
