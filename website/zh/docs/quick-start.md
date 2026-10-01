# 快速开始

> ⚠️ Axion 当前处于 **pre-alpha** 阶段（核心阶段，单机私有部署），API 可能变动。

## 环境要求

- **Java 21+**
- **Maven 3.8+**

核心阶段只需要 Java + Maven。无需 PostgreSQL、Redis 等外部依赖——数据存储在本地 SQLite。

## 安装

```bash
# 克隆仓库
git clone https://github.com/yALearner/Axion.git
cd axion

# 编译打包
mvn clean package -DskipTests

# 初始化工作区
java -jar axion-boot/target/axion-boot-*.jar init
```

## 配置第一个 Agent

1. 设置 LLM API Key：

```bash
export DEEPSEEK_API_KEY=sk-your-key-here
```

2. 编辑 `.axion/agents/default/AGENT.md`：

```markdown
---
name: default
description: 我的第一个 Agent
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
你是一个有用的助手，可以读写文件、执行命令、搜索信息。
```

## 开始使用

```bash
# 交互式对话
java -jar axion-boot/target/axion-boot-*.jar chat

# 或启动 HTTP API 服务
java -jar axion-boot/target/axion-boot-*.jar serve --port 8080

# 然后调用 API
curl -X POST http://localhost:8080/api/v1/sessions \
  -H "Content-Type: application/json" \
  -d '{"profileName":"default","channel":"web","userId":"demo"}'
```

## CLI 命令

```bash
axion init                      # 初始化 .axion/ 工作区（幂等）
axion status                    # 查看配置和运行状态
axion chat [--profile <name>]   # 交互式多轮对话
axion serve [--port 8080]       # 启动 HTTP API 服务
axion profile list              # 列出所有 Agent
axion tool list                 # 列出可用 Tool
axion session list              # 列出活跃 Session
```

## 下一步

- [系统架构](./architecture) — 理解 Axion 的内部设计
- [功能特性](./features) — 探索全部能力
- [使用场景](./scenarios) — 真实企业落地案例
