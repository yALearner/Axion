# What is Axion?

**Axion** is an open-source, Java-native **Agent OS** (Agent Operating System). You deploy it on your own servers or K8s cluster to configure, run, and monitor multiple AI Agents. All data stays on your infrastructure, every action is auditable, and there's zero cloud lock-in.

> Axion is named after the **Arabian Axion** — a resilient animal that thrives in harsh desert environments. Like its namesake, Axion is built to be a reliable, controllable Agent runtime in the harshest enterprise environments: strict regulation, high security, and zero tolerance for data leakage.

## Why Axion Exists

If you're trying to bring AI Agents into your enterprise today, you face three choices — none of them great:

| Approach | Problem |
|----------|---------|
| **SaaS platforms** (Coze, Kouzi) | Data leaves the enterprise. Compliance fails for regulated industries. |
| **Open-source Agent projects** (OpenClaw, Hermes Agent) | Built on Node.js / Python. Don't fit the Java ecosystem. Enterprise governance (RBAC, SSO, audit) is missing. |
| **DIY with frameworks** (LangChain, Spring AI) | Frameworks only handle LLM calls. Channels, memory, multi-tenancy, auditing — all on you. |

Axion fills exactly this gap: **a Java-native, turnkey runtime where Agents run persistently, governed, and fully auditable.**

## Core Positioning: Runtime, not Orchestration

```
┌──────────────────────────────────────────────┐
│       Orchestration Layer (Dify / Coze)       │  ← Axion doesn't do this
│          Visual Workflow, Drag-and-Drop        │
├──────────────────────────────────────────────┤
│         Axion · Agent Runtime                │  ← We are here
│    Agent Config | Channel | Memory | Tool     │
│              Audit | Sandbox                   │
├──────────────────────────────────────────────┤
│       Framework Layer (Spring AI / LangChain) │  ← Axion leverages this
│          LLM Calls, Prompt Templates, RAG      │
└──────────────────────────────────────────────┘
```

Axion **does runtime, not orchestration.** It leverages Spring AI for LLM calls, hosts Dify/Coze as the orchestration layer, and focuses on making Agents run reliably, governed, and auditable in the enterprise.

## Who Axion Is For

Axion is designed for **regulated industries** — banking, government, telecommunications, energy, healthcare — where:

- Core business data must never leave the infrastructure
- Every system action must be fully auditable
- The tech stack must align with existing Java operations

## Next Steps

- [Quick Start](./quick-start) — get Axion running in 5 minutes
- [Architecture](./architecture) — understand the 9-module design
- [Features](./features) — explore all capabilities
- [Scenarios](./scenarios) — real-world enterprise use cases
