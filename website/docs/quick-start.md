# Quick Start

> ⚠️ Axion is currently in **pre-alpha** (core phase, single-node private deployment). APIs may change.

## Prerequisites

- **Java 21+**
- **Maven 3.8+**

That's it. No PostgreSQL, Redis, or other external dependencies — data is stored in local SQLite.

## Installation

```bash
# Clone the repository
git clone https://github.com/yALearner/Axion.git
cd axion

# Build
mvn clean package -DskipTests

# Initialize the workspace
java -jar axion-boot/target/axion-boot-*.jar init
```

## Configure Your First Agent

1. Set your LLM API key:

```bash
export DEEPSEEK_API_KEY=sk-your-key-here
```

2. Edit `.axion/agents/default/AGENT.md`:

```markdown
---
name: default
description: My first Agent
provider:
  name: deepseek
  model: deepseek-chat
  api_key: ${DEEPSEEK_API_KEY}
tools:
  - read_file
  - write_file
  - shell
  - http_get
  - save_memory
  - recall_memory
settings:
  max_iterations: 10
---
You are a helpful assistant that can read/write files,
execute commands, and search for information.
```

## Start Using

```bash
# Interactive chat
java -jar axion-boot/target/axion-boot-*.jar chat

# Or start the HTTP API server
java -jar axion-boot/target/axion-boot-*.jar serve --port 8080

# Then call the API
curl -X POST http://localhost:8080/api/v1/sessions \
  -H "Content-Type: application/json" \
  -d '{"profileName":"default","channel":"web","userId":"demo"}'
```

## CLI Commands

```bash
axion init                      # Initialize .axion/ workspace (idempotent)
axion status                    # View configuration and runtime status
axion chat [--profile <name>]   # Interactive multi-turn chat
axion serve [--port 8080]       # Start HTTP API server
axion profile list              # List all Agents
axion tool list                 # List available Tools
axion session list              # List active Sessions
```

## What's Next

- [Architecture](./architecture) — understand how Axion works under the hood
- [Features](./features) — explore all capabilities
- [Scenarios](./scenarios) — see real-world enterprise use cases
