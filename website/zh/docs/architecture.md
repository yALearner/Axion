# 系统架构

Axion 由 **9 个 Maven 模块**组成，分四层，依赖方向清晰：

```
storage ← core ← provider / memory / tool ← channel-cli / web ← cli ← boot
```

## 模块总览

| 模块 | 层次 | 职责 |
|------|------|------|
| `axion-core` | 内核 | 核心抽象：`ReActLoop`、`PromptBuilder`、`ToolExecutor`、`AgentService`、`AgentScheduler` |
| `axion-storage` | 内核 | SQLite 持久化、`SessionRepository`、`ToolInvocationRepository`、`LlmCallRepository` |
| `axion-provider` | 能力 | `ProviderService`、多 Provider 显式映射、Function Calling 适配 |
| `axion-memory` | 能力 | `MemoryService` 统一门面、`LongTermMemory` 可插拔后端、`MemoryTools` |
| `axion-tool` | 能力 | 9 个内置 Tool、MCP Client、`ToolRegistry`、`Sandbox` 接口、`NotifyTools` |
| `axion-channel-cli` | 通道 | CLI 交互式对话 Channel |
| `axion-web` | 通道 | REST API（10 个端点）、`GlobalExceptionHandler`、OpenAPI 文档 |
| `axion-cli` | 启动 | Picocli 命令行入口（12 个子命令）、`ConfigLoader` |
| `axion-boot` | 启动 | Spring Boot 主类、自动配置、依赖聚合 |

## ReAct 循环

核心引擎是 **ReAct Loop**——Axion 自己实现，不依赖 Spring AI 的 Agent 抽象：

```
用户消息（从 CLI / HTTP / AgentScheduler 进来）
  → 追加到 Session 对话历史
  → PromptBuilder 组装 Prompt：
      [1] system prompt（AGENT.md 正文 + Skill 元数据 + Bootstrap）
      [2] 长期记忆（MEMORY.md 全文，每次重新读取）
      [3] 对话历史（最近 max_history_turns 轮）
      [4] 当前日期时间（LLM 自己不知道今天几号）
      [5] 可用 Tool 列表（Function Calling 格式）
  → ProviderService 调 LLM（写 llm_calls 表）
  → [无 Tool 调用] → 返回最终响应
  → [有 Tool 调用] → ToolExecutor 执行 Tool
      → Sandbox.enforce() 白名单校验
      → 执行（内置 Tool 在进程内 / MCP Tool 通过 JSON-RPC 转发）
      → 写 tool_invocations 表
      → 结果包装成 ToolResult 追加到对话历史
  → 回到组装 Prompt 继续循环（最多 max_iterations 次，默认 10）
```

## 设计原则

- **底座优先于 Agent**：最重要的交付不是某个强大的 Agent，而是让任意 Agent 可靠运行的环境
- **自实现核心，复用管道**：ReAct 循环手写；LLM 协议适配委托给 Spring AI
- **一个目录 = 一个 Agent**：一个业务 Agent 由一个目录定义——`AGENT.md`（frontmatter 配置 + 正文指令）、可选 `skills/` 软连接与 `scripts/`
- **对接开放标准**：工具用 MCP，Agent 目录借 Anthropic Agent Skills 的形态
- **无状态实例，状态外置**：这是未来走向分布式架构而不需要大改设计的前提
- **安全是地基，不是补丁**：工具来源管控、最小权限、强制沙箱白名单、凭证走环境变量、完整审计从第一天写入
