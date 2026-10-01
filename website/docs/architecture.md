# Architecture

Axion is organized into **9 Maven modules** across 4 layers, with clear dependency direction:

```
storage ← core ← provider / memory / tool ← channel-cli / web ← cli ← boot
```

## Module Overview

| Module | Layer | Responsibility |
|--------|-------|---------------|
| `axion-core` | Kernel | Core abstractions: `ReActLoop`, `PromptBuilder`, `ToolExecutor`, `AgentService`, `AgentScheduler` |
| `axion-storage` | Kernel | SQLite persistence, `SessionRepository`, `ToolInvocationRepository`, `LlmCallRepository` |
| `axion-provider` | Capability | `ProviderService`, multi-provider explicit mapping, Function Calling adapter |
| `axion-memory` | Capability | `MemoryService` facade, `LongTermMemory` pluggable backends, `MemoryTools` |
| `axion-tool` | Capability | 9 built-in Tools, MCP Client, `ToolRegistry`, `Sandbox` interface, `NotifyTools` |
| `axion-channel-cli` | Channel | CLI interactive chat Channel |
| `axion-web` | Channel | REST API (10 endpoints), `GlobalExceptionHandler`, OpenAPI docs |
| `axion-cli` | Boot | Picocli entry point (12 subcommands), `ConfigLoader` |
| `axion-boot` | Boot | Spring Boot main class, auto-configuration, dependency aggregation |

## ReAct Loop

The core engine is the **ReAct Loop** — Axion implements this itself (does not delegate to Spring AI's Agent abstraction):

```
User Message (from CLI / HTTP / AgentScheduler)
  → Append to Session history
  → PromptBuilder assembles the prompt:
      [1] System prompt (AGENT.md body + Skill metadata + Bootstrap)
      [2] Long-term memory (MEMORY.md, re-read every cycle)
      [3] Conversation history (last max_history_turns)
      [4] Current date/time (LLM doesn't know today's date)
      [5] Available Tool list (Function Calling format)
  → ProviderService calls LLM (writes llm_calls table)
  → [No tool call] → Return final response
  → [Has tool call] → ToolExecutor executes Tool
      → Sandbox.enforce() whitelist check
      → Execute (built-in Tools in-process / MCP Tools via JSON-RPC)
      → Write tool_invocations table
      → Wrap result as ToolResult, append to history
  → Loop back to Prompt assembly (max max_iterations, default 10)
```

## Design Principles

- **Runtime over Agents**: The most important deliverable isn't a specific powerful Agent, but the environment that lets any Agent run reliably.
- **Self-implement core, reuse pipes**: ReAct Loop is hand-written; LLM protocol adaptation is delegated to Spring AI.
- **One directory = one Agent**: A business Agent is defined by one directory — `AGENT.md` (frontmatter config + task instructions), optional `skills/` symlinks and `scripts/`.
- **Open standards**: Tools use MCP; Agent directories follow Anthropic Agent Skills conventions.
- **Stateless instances, external state**: Foundation for future distributed architecture without redesign.
- **Security is foundation, not patch**: Tool source control, least privilege, mandatory sandbox whitelist, credentials via environment variables, full audit from day one.
