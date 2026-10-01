# Data Model: 005-tool

> 本课**无新表**——MCP 配置走工作区文件、审计复用既有表。本文档记录两个核心数据结构的口径与替换契约。

## ToolRegistry（工具注册表，内存态）

| 维度 | 口径 |
|------|------|
| 存储 | 内存 `ConcurrentHashMap<String, AxionTool>`（虚拟线程并发读写安全；无共享可变状态之外的快照需求） |
| 键 | 工具名（`AxionTool.getName()`，全局唯一） |
| 注册来源 | ① 内置六件（装配处显式 @Bean 注入）② 方式二 MCP（McpClientService 启动包装注册）③ 方式三 @Tool（AnnotatedMethodToolAdapter 包装注册） |
| **重名规则** | 明确拒绝 + WARN（不静默覆盖——防 MCP 工具遮蔽内置工具；FR-1 自审补钉） |
| **过滤规则** | `filter(Profile)` 按 `profile.tools()` 精确匹配；Profile 声明了未注册的工具名 → 启动校验明确报错（001 同款纪律） |
| 消费方 | ToolExecutor（按名调度）、`axion tool list`（003 命令）、第 26 节 `/api/v1/tools`、AxionToolContractTest（参数化遍历） |

## McpServerConfig（`.axion/mcp_servers.yaml` 解析行，不可变 record）

| 字段 | 类型 | 说明 |
|------|------|------|
| `name` | String | server 注册名（WARN 日志带此名） |
| `transport` | String | 核心阶段仅 `stdio`（SSE 放扩展，编程指南 §4.4） |
| `command` | String | 启动命令（如 `npx`） |
| `args` | List\<String> | 命令参数（实现级明确：课件未点名，stdio 启动所需结构件） |
| `env` | Map\<String,String> | 环境变量；值支持 `${ENV_VAR}` 占位解析（001 机制复用）；凭证不明文 |

```yaml
# .axion/mcp_servers.yaml（003 init 已建模板，本节消费）
mcp_servers:
  - name: github-mcp
    transport: stdio
    command: npx
    args: ["-y", "@modelcontextprotocol/server-github"]
    env:
      GITHUB_TOKEN: ${GITHUB_TOKEN}
```

- **解析规则**：yaml 缺失/为空/结构非法 → 按"零 server"处理或明确报错（实现级明确：结构非法明确报错、空列表正常启动）；单个 server 连接失败不影响其他（坑十三）

## PermissiveSandbox（临时实现，替换契约）

| 维度 | 口径 |
|------|------|
| 定位 | `Sandbox` 接口的临时全放行实现（拍板方案 A）——`enforce(SandboxAction)` 空实现 |
| 生命周期 | 第 20 节装配处 @Bean → **第 24 节替换为 `WhitelistSandbox`**（javadoc 必须标注替换时机；替换时零改动调用方——接口不变） |
| 安全口径 | 20~23 节白名单未生效：内网假设 + `tool_invocations` 审计留痕 + 保守 Profile 纪律（shell/http_post 不进任何 Agent 的 tools 声明） |
| 归属 | 落 axion-tool（不是测试 stub，是拍板授权的生产临时件；24 节删除或保留为文档反例，实现级明确） |

## 既有实体复用（零新增）

- **AxionTool / ToolResult / JsonSchema**（001）：全部工具实现与返回值
- **Sandbox / SandboxAction / ActionType / SandboxViolationException**（002）：execute 首行 enforce 的契约四件套
- **Profile.tools**（001）：过滤输入
- **tool_invocations**（002）：审计复用（本课零新增审计逻辑）
- **notify_channels / NotifyTools 五件套**（004）：本课正式接线，口径不变
