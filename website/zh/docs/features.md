# 功能特性

## 核心阶段（当前）— Axion 1.0 运行时内核

### 🤖 Agent 是配置出来的
一个目录 = 一个 Agent。`AGENT.md` = frontmatter 配置 + 正文指令。不写 Agent 后端代码。

### 🔌 多 Provider 对接
基于 Spring AI，支持 DeepSeek、通义、Kimi、智谱、混元、豆包等十余个 Provider。显式映射确保运行时路由正确——切换模型只需改一行配置。

### 🧠 自实现 ReAct Loop
Axion 自己实现 Agent 大脑循环（不用 Spring AI 的自动 tool 执行）。完整掌控思考→行动→观察循环，保留未来定制循环行为的空间。

### 📝 三层记忆系统
- **会话记忆**：最近 `max_history_turns` 轮对话
- **长期记忆**：`MEMORY.md` 文件，核心区 + 归档区
- **可插拔后端**：Markdown（默认）、SQLite、自托管 Mem0——换后端只需改一行配置

### 🔧 Plugin Tool 体系
- **9 个内置 Tool**：`read_file`、`write_file`、`list_dir`、`shell`、`http_get`、`http_post`、`save_memory`、`recall_memory`、`notify`
- **三档扩展**：
  - 一档（零代码）：复用社区 MCP server
  - 二档（轻代码）：任意语言写 MCP server（JSON-RPC over stdio）
  - 三档（重代码）：Java `@Tool` 注解 Spring Bean

### 🛡️ Sandbox 沙箱
接口先行设计（`Sandbox.enforce(SandboxAction)`）。核心阶段：`WhitelistSandbox` 三层白名单（文件路径、Shell 命令、HTTP 域名）。扩展阶段可升级到容器/microVM，接口不变。

### 🌐 REST API
10 个核心端点，统一 `ApiResponse` 信封。会话管理、Agent 调用、Profile 查询、健康检查。

### ⏰ Cron 定时触发
Agent 按 cron 表达式到点自动运行——日报生成、定时巡检、数据同步。无需人工发起。

### 📋 审计 Day One
`tool_invocations` 和 `llm_calls` 表从第一天写入。每次 Tool 调用和 LLM 请求完整记录——可审计是 Axion 的核心差异化能力。

## 扩展阶段（社区共建）— 企业级治理

- 多租户 RBAC、SSO 单点登录
- 完整审计与 SIEM 导出
- 多 Channel 接入（企业微信、飞书、钉钉、Slack）
- Web 管理控制台
- 向量检索（pgvector）
- 容器级 Sandbox 隔离
- 集群化部署与高可用（Nacos / Sentinel / SkyWalking）
