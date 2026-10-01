# Features

## Core Phase (Current) — Axion 1.0 Runtime Kernel

### 🤖 Agent as Configuration
One directory = one Agent. `AGENT.md` = frontmatter config + task instructions. No Agent backend code to write.

### 🔌 Multi-Provider LLM
Based on Spring AI, supports DeepSeek, Tongyi, Kimi, Zhipu, Hunyuan, Doubao and more. Explicit provider mapping ensures correct routing at runtime — switch by changing one line of config.

### 🧠 Self-implemented ReAct Loop
Axion implements the Agent brain loop itself (not Spring AI's automatic tool execution). Complete control over the think→act→observe cycle, with full flexibility for custom loop behavior.

### 📝 Three-tier Memory
- **Session memory**: Last `max_history_turns` of conversation
- **Long-term memory**: `MEMORY.md` file, core + archival zones
- **Pluggable backends**: Markdown (default), SQLite, or self-hosted Mem0 — switch with one config line

### 🔧 Plugin Tool System
- **9 built-in Tools**: `read_file`, `write_file`, `list_dir`, `shell`, `http_get`, `http_post`, `save_memory`, `recall_memory`, `notify`
- **Three extension tiers**:
  - Tier 1 (zero-code): Reuse community MCP servers
  - Tier 2 (light-code): Write MCP server in any language (JSON-RPC over stdio)
  - Tier 3 (heavy-code): Java `@Tool` annotation Spring Beans

### 🛡️ Sandbox
Interface-first design (`Sandbox.enforce(SandboxAction)`). Core phase: `WhitelistSandbox` with three-tier whitelist (file paths, shell commands, HTTP domains). Extensible to container/microVM without interface changes.

### 🌐 REST API
10 core endpoints under `/api/v1`, unified `ApiResponse` envelope. Session management, Agent invocation, profile listing, health checks.

### ⏰ Cron Scheduler
Agents auto-run on cron schedules — daily reports, scheduled inspections, data sync. No human initiation required.

### 📋 Audit Day One
`tool_invocations` and `llm_calls` tables written from day one. Every tool call and LLM request is recorded — auditability is Axion's core differentiator.

## Extension Phase (Community) — Enterprise Governance

- Multi-tenant RBAC, SSO single sign-on
- Full audit trail with SIEM export
- Multi-channel access (WeCom, Feishu, DingTalk, Slack)
- Web management console
- Vector search (pgvector)
- Container-level sandbox isolation
- Cluster deployment & high availability (Nacos / Sentinel / SkyWalking)
