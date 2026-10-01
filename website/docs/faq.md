# FAQ

## What makes Axion different from Dify or Coze?

Axion is a **runtime**, not an orchestration platform. Dify and Coze are workflow orchestration tools — they let you visually design Agent workflows. Axion sits one layer below: it provides the runtime environment (Provider, ReAct, Memory, Tool, Audit, Sandbox) that Agents run on. In fact, Dify could run ON Axion as the orchestration layer.

## What makes Axion different from OpenClaw or Hermes Agent?

Same category, different positioning. All three use markdown + frontmatter directory structures to define Agents. The key differences:

- **Language ecosystem**: Axion is Java/Spring Boot — OpenClaw is Node.js, Hermes Agent is Python
- **Target audience**: Axion targets regulated enterprises (banking, government, healthcare). OpenClaw and Hermes Agent target individual developers to small teams.
- **Governance**: Axion bakes in audit, sandbox, and multi-tenancy from day one. These are afterthoughts in the other projects.

## Why Java? Why not Python or Node.js?

Because the target enterprises already run Java. Banks, governments, and telecom companies have Java-based infrastructure, Java operations teams, and Java security review processes. A Python Agent runtime would be a foreign object in their environment. Java 21's virtual threads provide the concurrency model needed for Agent workloads.

## Do I need PostgreSQL? Redis? Docker?

**No.** Core phase Axion needs only Java 21 + Maven. Data is stored in local SQLite. No external services, no containers, no cloud dependency. One fat JAR and you're running.

## How does Axion handle security?

Three layers:

1. **Sandbox**: All Tool calls go through `Sandbox.enforce()` before execution. Core phase: `WhitelistSandbox` with path/command/domain whitelists.
2. **Credentials**: API keys and sensitive config use `${ENV_VAR}` syntax — never hardcoded in Agent config files.
3. **Audit**: `tool_invocations` and `llm_calls` written from day one. Every action is traceable.

## Can I add my own Tools?

Yes, three ways:

1. **Zero-code**: Configure a community MCP server in `mcp_servers.yaml`
2. **Light-code**: Write an MCP server in any language (JSON-RPC over stdio)
3. **Heavy-code**: Write a Java class with `@Tool` annotation — in-process, best performance

## What LLM providers are supported?

DeepSeek, Tongyi (Qwen), Kimi, Zhipu (GLM), Hunyuan, Doubao, and more via Spring AI. Multi-provider coexistence with explicit mapping — different Agents can use different models.

## Is it production-ready?

No. Axion is currently in **pre-alpha** (core phase). APIs may change. It is not recommended for production use yet.

## How do I contribute?

Axion welcomes all forms of contribution: code, docs, issues, discussions.

1. Fork the repository
2. Create a feature branch (`git checkout -b feat/amazing-feature`)
3. Commit changes (`git commit -m 'feat: add amazing feature'`)
4. Push (`git push origin feat/amazing-feature`)
5. Create a Pull Request

Read [CLAUDE.md](https://github.com/yALearner/Axion/blob/main/CLAUDE.md) for the project constitution and coding principles.
