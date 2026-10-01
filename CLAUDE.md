# Axion — Claude Code 项目指南

Axion 是用 Java 实现的面向企业场景的 **Agent OS**（Agent 统一底座）。装在企业自己的 K8s 或服务器上，作为统一底座运行多个业务 Agent，共享渠道接入、模型路由、工具调用、记忆系统、沙箱执行能力。数据完全留在企业自己的基础设施，不锁任何云生态。Agent 在 Axion 上是**配置出来的，不是写代码写出来的**。

> 详细背景：`docs/IndustryResearch.md`（业界调研）、`docs/DemandAnalysis.md`（需求）、`docs/TechnicalSolution.md`（技术方案）、`docs/AiProgrammingGuide.md`（AI 编程指南）

---

## 核心定位：做运行时，不做编排

```
编排层（Dify / Coze）→ 可视化 Workflow    ← Axion 不做
────────────────────────────────────────
Axion · Agent 运行时                    ← 我们在这一层
Agent 配置 | Channel | Memory | Tool | 审计
────────────────────────────────────────
框架层（Spring AI / LangChain）→ LLM 调用 ← Axion 复用
```

- Axion **复用** Spring AI / Spring AI Alibaba 做 LLM 调用
- Axion **托管** Dify / Coze 等编排平台（编排平台作为客户端调 Axion API）
- Axion **专注** 运行时：让 Agent 能常驻、可治理、可审计地跑起来

**目标场景**：银行、政府、电信、能源、医疗等严监管行业——核心业务数据不能出企业、系统必须完全可审计、技术栈要跟现有 Java 体系对齐。

---

## 技术栈

| 组件 | 选型 |
|------|------|
| 语言 / 运行时 | Java 21（必须，virtual thread 处理并发） |
| 框架 | Spring Boot 3.x |
| LLM 调用 | Spring AI Alibaba（**仅用协议转换 + `@Tool` schema 生成**） |
| HTTP 服务 | Spring MVC + Java 21 Virtual Thread |
| 命令行 | Picocli |
| YAML 解析 | SnakeYAML |
| 持久化 | SQLite + Spring Data JPA（核心阶段）；PostgreSQL + pgvector / Redis / Nacos 等放扩展阶段 |
| 日志 | Logback + SLF4J（结构化 JSON） |
| 构建 | Maven 多模块 |

---

## 模块结构（9 个）

```
axion/
├── axion-core          # 核心抽象：AxionTool 接口、Session、Profile、ContextLoader、
│                        #   AgentLoader、ReActLoop、PromptBuilder、ToolExecutor、AgentService、
│                        #   ProfileContext、SessionManager、AgentScheduler、ToolSchemaAdapter、
│                        #   LlmGateway（依赖倒置端口）
├── axion-provider      # 能力一：ProviderService、
│                        #   多 Provider 显式映射（provider name → ChatModel）
├── axion-memory        # 能力三：MemoryService 统一门面、LongTermMemory 可插拔后端、
│                        #   MemoryTools（save_memory / recall_memory）
├── axion-tool          # 能力四：内置 Tool（FileTools / ShellTools / HttpTools / NotifyTools）、
│                        #   McpClientService、McpToolAdapter、ToolRegistry、
│                        #   Sandbox 接口 + WhitelistSandbox、NotifyChannelAdapter + WebhookNotifyAdapter
├── axion-channel-cli   # CLI Channel：CliChannel、axion chat 命令实现
├── axion-web           # 能力五：WebServer、6 个 ApiController、GlobalExceptionHandler、OpenAPI
├── axion-storage       # 持久化：SQLite、SessionRepository、
│                        #   ToolInvocationRepository、LlmCallRepository
├── axion-cli           # 命令行入口：Picocli 主入口、12 个子命令、ConfigLoader
└── axion-boot          # Spring Boot 启动模块：主类、自动配置、依赖聚合
```

模块之间通过接口解耦。新增 Channel 或 Tool 只加新模块，不改 `axion-core`。

**依赖方向**：`axion-storage` 被 core 和能力层依赖；`axion-provider` / `axion-memory` / `axion-tool` 依赖 core；`axion-web` / `axion-channel-cli` 依赖所有能力层；`axion-cli` 组装所有模块；`axion-boot` 做依赖聚合。

---

## 不可违背的原则（Constitution）

以下原则来自 `docs/AiProgrammingGuide.md` 和 `docs/TechnicalSolution.md`，所有代码必须遵守。

### 原则一：自实现 ReAct Loop

`ReActLoop` 必须自己实现，**不得**使用 Spring AI 的 Agent 抽象（如 `ChatClient.prompt().call()` 的自动工具执行）。核心循环约数十行 Java，完整掌握 Agent 工作机制，保留未来定制循环行为的空间。

### 原则二：Spring AI 只用两件事 ⚠️

Spring AI 在 Axion 里只做：

1. LLM Provider 协议转换（OpenAI / Anthropic / Gemini 等各家格式差异由它吸收）
2. `@Tool` 注解的 JSON Schema 生成

**必须禁用** Spring AI 的自动 tool 执行。Tool 的调度和执行完全由 `ReActLoop` + `ToolExecutor` 控制。违反此原则会导致 tool 被调两次。

```java
// 错误：不得用 Spring AI 自动执行 tool
chatClient.prompt(prompt).tools(tools).call().content();

// 正确：只用 Spring AI 做 LLM 调用，tool 调用结果自己处理
ChatResponse response = chatModel.call(new Prompt(messages, options));
// 然后自己检查 response 里的 tool call，自己执行
```

### 原则三：Provider 必须显式映射

多 Provider 并存时，**不得**靠扫描 Spring 容器里的 `ChatModel` Bean 类型来区分 Provider（Bean 类型相同、Bean name 未必等于 provider name）。必须维护 `provider name → ChatModel` 的显式映射表：

```java
// 正确：显式映射
Map<String, ChatModel> providerMap = Map.of(
    "deepseek", deepseekChatModel,
    "qwen",     qwenChatModel,
    "kimi",     kimiChatModel
);
```

### 原则四：一个目录 = 一个 Agent；Skill 以本地软连接绑定并渐进披露

**一个目录 = 一个 Agent**：`.axion/agents/<name>/` 里 `AGENT.md` = frontmatter（运行配置）+ 正文（任务指令），外加可选 `skills/`（Skill 绑定视图）、`scripts/`、`REFERENCE.md`。`AgentLoader.deriveProfile(agentDir)` 把 frontmatter 派生成底座认识的 `Profile`。

**底座 vs Agent 分清楚**：底座 = Provider、ReAct、内置 Tool、Memory、Sandbox、定时、Web（第 1~10 章），所有 Agent 共享。Agent = 一个目录，决定自己的运行配置和可见资源。

公共 Skill 实体统一存放在 `.axion/skills/<name>/`。Agent 可见的 Skill 只由 `.axion/agents/<agent>/skills/<name>` 下指向公共实体的**相对软连接**表达；软连接集合是唯一绑定真相源，`AGENT.md` frontmatter 不再声明 `skills:`。

加载走三层渐进式披露：每轮 prompt 只注入当前 Agent 已绑定 Skill 的 `name + description + 本地绝对读取路径`；模型命中后用 `read_file` 读取 `SKILL.md` 正文；Skill 附属参考/脚本继续按需读取或运行。不得预载正文、不得新增 `use_skill`、Skill 不进 `ToolRegistry`。

### 原则五：审计表 Day One 写入

`tool_invocations` 和 `llm_calls` 两张审计表**核心阶段就必须写入**（不需要查询接口，但写入不能省）。不得以"日志够了"为由跳过落库，可审计是 Axion 的核心差异化能力。

### 原则六：不使用 Java SecurityManager

`SecurityManager` 在 JDK 17 起废弃、JDK 21 已不可用。Sandbox 通过 **接口先行** 实现——`Sandbox` 接口只有一个方法 `enforce(SandboxAction action)`（`ActionType` = `FILE_READ | FILE_WRITE | SHELL_COMMAND | HTTP_REQUEST`），不携带任何实现细节。核心阶段 `WhitelistSandbox` 按三层白名单校验：
- 文件操作：路径白名单（`file.allowed_paths`），需处理 `../` 路径穿越
- Shell：命令首 token 白名单（`shell.allowed_commands`）
- HTTP：域名通配符白名单（`http.allowed_domains`）

扩展阶段按信号驱动升级到容器/microVM，接口不变，只新增实现类。

### 原则七：同步执行模型

核心阶段全程同步阻塞，配合 Java 21 Virtual Thread 处理并发。**不引入** Reactor / WebFlux / CompletableFuture 等异步编程模型（SSE 流式响应放扩展阶段）。

### 原则八：三种触发源共用一个引擎

CLI（人推）、Web Service（人推）、`AgentScheduler`（钟推）三个入口最终都汇入同一个 `AgentService.process()`，`ReActLoop` 不感知消息从哪个入口来。钟推的 Session 中 channel 和 user 都固定为 `scheduler`。

### 原则九：Tool 模块三合一

内置 Tool、MCP Client、Sandbox、NotifyTools 合并在一个 `axion-tool` 模块，**不拆成多个模块**。`AGENT.md` 正文加载归 `axion-core` 的 `ContextLoader`（Agent 目录不是 Tool）。

---

## 工作区结构（运行时）

Axion 启动后在当前目录创建 `.axion/` 工作区：

```
.axion/
├── agents/             # 每个子目录 = 一个 Agent（AGENT.md + skills/软连接 + scripts/ REFERENCE.md）
├── skills/             # 公共 Skill 实体库：每个子目录 = 一个 Skill（SKILL.md + 可选附属资源）
├── memory/
│   └── MEMORY.md       # 长期记忆（Agent 通过 save_memory 写入，分核心记忆区 + 归档记忆区）
├── sessions/           # 会话数据（已迁入 SQLite，此目录备用）
├── logs/               # 结构化日志
├── mcp_servers.yaml    # MCP server 配置
├── axion.db           # SQLite 数据库
├── AGENTS.md           # Bootstrap：项目级 agent 行为说明
├── SOUL.md             # Bootstrap：agent 人格定义
└── USER.md             # Bootstrap：用户偏好（只读，agent 不写）
```

**`MEMORY.md` vs `USER.md` 区别**：
- `USER.md`：用户手写的初始设定，Axion 只读不写
- `MEMORY.md`：Agent 通过 `save_memory` Tool 写入的成长记录，Axion 读写

---

## 核心数据模型

### AGENT.md（`.axion/agents/<name>/AGENT.md`）

一个 Agent 目录里 `AGENT.md` = frontmatter（这个 Agent 自己的 profile）+ 正文（任务指令）。`AgentLoader.deriveProfile(agentDir)` 把 frontmatter 派生成底座认识的 `Profile`。

```markdown
---
name: ops-agent
description: 运维助手
identity:
  agent_name: 运维小欧
  prompt: 你是一个专业的运维助手...
provider:
  name: deepseek          # 对应 ProviderService 里的显式映射 key
  model: deepseek-chat
  temperature: 0.7
  api_key: ${DEEPSEEK_API_KEY}   # 从环境变量读取，不明文写死
tools:
  - read_file
  - shell
  - http_get
  - save_memory
  - recall_memory
mcp_servers:
  - github-mcp
channels:
  - name: cli
bootstrap:
  - AGENTS.md
  - SOUL.md
  - USER.md
settings:
  max_iterations: 10
  max_history_turns: 20
schedules:                # 可选定时触发（AgentScheduler 钟推）
  - id: weather-8am        # 任务唯一标识（010 补入）：锁 key 与 task_id 直接用 id；跨 Profile 冲突、缺失均启动报错
    cron: "0 0 8 * * *"   # Spring 6 六段含秒（008-scheduler 实测：五段启动报错）
    zone: Asia/Shanghai
    message: 生成今日天气和穿搭建议
---
你是一个专业的运维助手。被触发时……（Agent 的任务指令正文，注入 system prompt）
```

### SQLite 核心表

**sessions**

| 字段 | 类型 | 说明 |
|------|------|------|
| `session_id` | VARCHAR PK | channel+user+profile 联合生成 |
| `profile_name` | VARCHAR | 关联 Profile |
| `channel` | VARCHAR | 接入渠道 |
| `user_id` | VARCHAR | 用户标识 |
| `messages_json` | TEXT | JSON 序列化的对话历史 |
| `status` | VARCHAR | `active` / `archived` |
| `created_at` | TIMESTAMP | 创建时间 |
| `last_active_at` | TIMESTAMP | 最后活跃时间 |
| `archived_at` | TIMESTAMP | 归档时间（可空） |

**tool_invocations**（审计，day one 写入）

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | BIGINT PK | 主键 |
| `session_id` | VARCHAR | 关联 Session |
| `tool_name` | VARCHAR | Tool 名称 |
| `input_json` | TEXT | 调用参数 |
| `result_json` | TEXT | 执行结果 |
| `success` | BOOLEAN | 是否成功 |
| `error_message` | TEXT | 错误信息（可空） |
| `duration_ms` | BIGINT | 执行耗时 |
| `created_at` | TIMESTAMP | 调用时间 |

**llm_calls**（审计，day one 写入）

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | BIGINT PK | 主键 |
| `session_id` | VARCHAR | 关联 Session |
| `provider` | VARCHAR | Provider 名称 |
| `model` | VARCHAR | 模型名 |
| `prompt_tokens` | INT | 输入 token 数 |
| `completion_tokens` | INT | 输出 token 数 |
| `total_tokens` | INT | 总 token 数 |
| `duration_ms` | BIGINT | 调用耗时 |
| `created_at` | TIMESTAMP | 调用时间 |

> **SQLite 迁移注意**：`hibernate.ddl-auto=update` 在 SQLite 上 `ALTER TABLE` 支持很弱。表结构变更时**不要**依赖 Hibernate 自动迁移，手动维护建表脚本或引入 Flyway。
>
> **SQLite 类型落地**：SQLite 无原生 TIMESTAMP，两表 `created_at` 以 **ISO-8601 TEXT** 存储（JPA `Instant` + AttributeConverter 映射），语义仍是"调用时间"。

---

## ReAct Loop 工作机制

```
用户消息（从 CLI / HTTP / AgentScheduler 进来）
  → 追加到 Session 对话历史
  → PromptBuilder 组装 Prompt：
      [1] system prompt（AGENT.md 正文 + 已绑定 Skill 元数据 + Bootstrap）  ← ContextLoader
      [2] 长期记忆（MEMORY.md 全文，每次重新读取不缓存）                    ← MemoryService
      [3] 对话历史（最近 max_history_turns 轮）                             ← SessionManager
      [4] 当前日期时间（LLM 自己不知道今天几号）                              ← PromptBuilder
      [5] 可用 Tool 列表（Function Calling 格式）                           ← ToolRegistry
  → ProviderService 调 LLM（写 llm_calls 表）
  → [无 Tool 调用] → 返回最终响应
  → [有 Tool 调用] → ToolExecutor 执行 Tool
      → Sandbox.enforce() 白名单校验
      → 执行（内置 Tool 在进程内 / MCP Tool 通过 JSON-RPC 转发）
      → 写 tool_invocations 表
      → 结果包装成 ToolResult 追加到对话历史
  → 回到组装 Prompt 继续循环（最多 max_iterations 次，默认 10）
```

---

## Tool 体系

### AxionTool 接口（所有 Tool 的统一抽象）

```java
interface AxionTool {
    String getName();
    String getDescription();
    JsonSchema getInputSchema();
    ToolResult execute(JsonNode input);
}
```

`ToolResult` 包含：`success`、`content`、`errorMessage`、`retryable`。

### 内置 Tool（核心阶段 9 个）

| Tool | 类 | 说明 |
|------|-----|------|
| `read_file` | `FileTools` | 读文件，路径白名单 |
| `write_file` | `FileTools` | 写文件，路径白名单 |
| `list_dir` | `FileTools` | 列目录，路径白名单 |
| `shell` | `ShellTools` | 执行 bash，命令白名单 + 超时 |
| `http_get` | `HttpTools` | GET 请求，域名白名单 |
| `http_post` | `HttpTools` | POST 请求，域名白名单 |
| `save_memory` | `MemoryTools` | 追加到 MEMORY.md（可指定 scope: core/archival） |
| `recall_memory` | `MemoryTools` | 关键词检索 MEMORY.md 归档区 |
| `notify` | `NotifyTools` | 推送消息到通知渠道，核心阶段走 WebhookNotifyAdapter |

### Plugin Tool 三档

| 方式 | 门槛 | 推荐 | 实现 |
|------|------|------|------|
| 零代码 | 最低 | ⭐ 主推 | 写 Agent 目录（AGENT.md）+ 复用社区 MCP server，`mcp_servers.yaml` 里配置 |
| 轻代码 | 中 | ⭐⭐ | 任意语言写 MCP server（JSON-RPC over stdio），Axion 作为 MCP Client 连接 |
| 重代码 | 高 | ⭐⭐⭐ | Java `@Tool` 注解 Spring Bean，进程内直接调用，性能最好 |

> 选择原则：能用方式一就不用方式二，能用方式二就不用方式三。

---

## Web Service API

核心阶段 10 个端点，统一前缀 `/api/v1`，响应信封统一为 `ApiResponse`（`code`、`message`、`data`、`timestamp`）：

| 方法 | 路径 | 说明 |
|------|------|------|
| `POST` | `/sessions` | 创建会话 |
| `POST` | `/sessions/{id}/messages` | 发消息（触发 ReAct Loop） |
| `GET` | `/sessions/{id}` | 查会话历史 |
| `DELETE` | `/sessions/{id}` | 归档会话 |
| `POST` | `/agents/{name}/invoke` | 无状态调用 Agent |
| `GET` | `/profiles` | 列所有 Profile |
| `GET` | `/memory` | 查长期记忆（MEMORY.md） |
| `GET` | `/tools` | 列可用 Tool |
| `GET` | `/health` | 健康检查 |
| `GET` | `/info` | 运行信息 + Provider 状态 |

**核心阶段不做**：认证（假设内网）、SSE 流式、WebSocket、限流、RBAC。

---

## 命令行工具（12 个）

```bash
# 启动和状态
axion init                      # 初始化 .axion/ 工作区（幂等）
axion status                    # 查看配置和运行状态
axion chat [--profile <name>]   # 交互式多轮对话（--message "xxx" 发单条后退出）
axion serve [--port 8080]       # 启动 HTTP API 服务（定时任务随 serve/gateway 常驻）
axion gateway                   # 守护进程模式（多 Channel）

# Agent 管理（命令组名沿用 profile，操作的是 .axion/agents/ 下目录）
axion profile list
axion profile create <name>
axion profile show <name>
axion profile delete <name>

# 查询
axion provider list
axion tool list
axion session list
```

三种模式（chat / serve / gateway）共享同一份 Profile 配置和 Session 存储。

---

## 配置加载规则

敏感配置（API key、MCP server 凭证）通过环境变量注入，**不得**明文写在 Profile YAML 里：

```yaml
provider:
  name: deepseek
  api_key: ${DEEPSEEK_API_KEY}   # 从环境变量读取
```

`ConfigLoader` 启动时做必填项和格式校验，缺失或非法时给清晰报错，不静默失败。完整的加密存储、密钥轮转、对接企业 KMS/Vault 放扩展阶段。

---

## Memory 三层可插拔后端

长期记忆抽成 `LongTermMemoryStore` 接口（`append` / `load` / `recallByKeyword`），核心阶段一次交付三档实现，靠 `memory.backend` 配置切换：

| 后端 | 存储 | 特点 |
|------|------|------|
| `MarkdownMemoryStore`（默认） | `.axion/memory/MEMORY.md` 文件，核心区+归档区 | 零依赖、人可读、git 可跟踪 |
| `SqliteMemoryStore` | `memory_entries` 表 | 复用已有 SQLite，结构化查询 |
| `Mem0MemoryStore` | 自托管 Mem0（数据不出域） | 语义检索、自动提炼、冲突消解 |

换后端只改一行配置，`MemoryService` 以上代码一个字不动。核心阶段**不做**自动从对话中抽取事实——分区完全由 Agent 通过 `save_memory` 调用时的 `scope` 参数手动决定。

---

## 五大核心能力与验收 Demo

| 能力 | 核心组件 | 验收 Demo |
|------|---------|---------|
| **一：对接 LLM** | `ProviderService`，显式 provider 映射 | —（US-2 完成后一起演示） |
| **二：ReAct 循环** | `ReActLoop`、`PromptBuilder`、`ToolExecutor` | Demo 一：每日天气，到点自动查天气+穿搭建议推送 |
| **三：Memory** | `MemoryService`、`LongTermMemory`、`MemoryTools` | Demo 二：每日科技日报，日报体现用户偏好 |
| **四：Plugin Tool** | `ToolRegistry`、MCP Client、Sandbox | Demo 三：每日 GitHub 日报，Agent 目录 + scripts/ |
| **五：Web Service** | `WebServer`、6 个 ApiController | 10 个 REST 端点完整调用 |

三个 Demo 都是"钟推"（AgentScheduler 到点自动触发），但都同时支持"人推"手动补跑验证。两个 Demo 跑通是核心功能发布的**硬条件**。

---

## 四周实施节奏

| 周次 | 核心任务 | 涉及模块 | 验收 Demo |
|------|---------|---------|----------|
| 第一周 | Provider 抽象 + ReAct Loop | `axion-core` `axion-provider` `axion-channel-cli` `axion-cli` | `axion chat` 多轮对话，Agent 调 HTTP Tool |
| 第二周 | Memory + Tool 体系 | `axion-memory` `axion-tool` | Agent 跨对话记偏好；调本地文件和外部 MCP server |
| 第三周 | Web Service | `axion-web` `axion-storage` | 10 个 REST 端点完整调用 |
| 第四周 | 多 Agent 演示 + 工程化收尾 | 所有模块 | 多 Agent 并存；Session 跨重启恢复；定时任务到点触发；项目主页可访问 |

---

## 常见陷阱

| 陷阱 | 症状 | 修复 |
|------|------|------|
| Spring AI 自动执行 tool | Tool 被调两次，结果重复 | 禁用 `ChatClient` 的自动 tool 执行，由 `ToolExecutor` 接管 |
| Provider 靠类型扫描区分 | 多 Provider 时路由错乱 | 改用显式 `Map<String, ChatModel>` 映射 |
| `AGENT.md` / Agent 目录放进 Tool 模块 | Agent 目录被当 Tool 注册，执行时报错 | 归 `ContextLoader`（core 模块）：正文注入 system prompt，子资源经 read_file/shell 按需取 |
| 审计表只写日志不落库 | 扩展阶段审计功能需要反解析日志 | `tool_invocations` + `llm_calls` 核心阶段就写入 SQLite |
| 用 `hibernate.ddl-auto=update` 迁移 SQLite 表结构 | SQLite ALTER TABLE 报错 | 手动维护建表脚本或引入 Flyway |
| 在 ReAct Loop 里用异步 | 复杂度激增，Virtual Thread 优势消失 | 保持同步阻塞，Virtual Thread 自动处理 IO 等待 |
| `MEMORY.md` 超过阈值不截断 | 注入 system prompt 超 context window | 核心区永远完整不截断，只截断归档区 |
| Tool 模块拆成多个 | 模块间依赖混乱 | 内置 Tool + MCP Client + Sandbox + NotifyTools 合并为 `axion-tool` |
| Sandbox 接口带了实现细节 | 换隔离方案时要改接口+所有调用方 | 接口只表达 `enforce(SandboxAction)`，不出现白名单/容器/VM 字样 |
| Memory 跟 Session 合并成一个概念 | ReAct 循环要分别问两个地方拿上下文 | `MemoryService` 统一门面收口，内部委托 SessionManager + LongTermMemory |
| 安装带脚本的 Agent 后忽略信任边界 | 脚本绕过 HTTP 域名白名单直接发网络请求 | `shell` 跑脚本 = 信任 Agent 作者；白名单只管解释器+脚本目录 |
| 忽略了定时任务第三种触发源 | 到点不触发 | `AgentScheduler` 基于 `ThreadPoolTaskScheduler` + `CronTrigger` 动态注册 |
| javadoc 注释里写 `*/` 序列（如 `last_*/run_count`） | 注释被提前终结，编译报「非法字符/找不到符号」且报错行号错位 | 用顿号或空格拆开：`last_*、run_count`（010 实录） |
| `mvn -pl 单模块` 不带 `-am` | 依赖模块取本地仓库旧 jar → NoClassDefFoundError/找不到符号（新类不可见） | 加 `-am` 或直接跑全 reactor（010 实录） |
| IDE 自动构建污染 `target/` | 运行时报 `Unresolved compilation problem`（JDT 产物，javac 增量跳过重编译） | `mvn clean` 后重跑（010 实录） |
| 同模块多个 @SpringBootTest 共用测试工作区 `.axion` | 测试类互踩：删除已打开中的 db 文件 Windows 报错、断言被污染 | 每个测试类专属 db 文件（@DynamicPropertySource 覆盖 url）+ agents 目录整体重建（010 实录） |
| 前端 `npm ci/build` 报 -4048 | `esbuild.exe` 被残留 vite/杀软进程占用，删不掉装不进 | 先 `Get-Process node` 强杀，再删 node_modules 重装（009/010 实录） |
| Spring `CronExpression.parse/next` 的 @Nullable 契约不判空 | SpotBugs NP 门禁拦（NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE），verify 红 | 两处显式判空抛 IllegalStateException（010 实录） |
| `Set.of(集合变量)` 编译报 `Set<List<String>>` 构造不匹配 | `Set.of` 只有元素重载，没有 Collection 重载——传一个 List 参数被解析成单元素泛型 | 集合入参用 `Set.copyOf(collection)`（011 实录） |
| 测试里把 Windows 路径手拼进 JSON 字符串 | 反斜杠不转义 → Jackson `Unrecognized character escape 'U'`，工具执行失败 | 用 `ObjectMapper.createObjectNode().put("path", path)` 构造参数节点（011 实录） |
| ErrorProne `-Werror` 报 StringConcatToTextBlock | spotless 把长字符串重排成 `+` 连接后撞 ErrorProne 门禁 | 直接写 Java text block（`"""..."""`），两者都满意（011 实录） |
| SpotBugs `NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE`：`getFileName() == null ? "" : getFileName().toString()` | @Nullable 方法二次调用判空无效——第二次调用仍可能返回 null | 单次取值入局部变量再判空（011 实录） |
| 给 ContextLoader/PromptBuilder 相关测试加新行为（如正文注入） | 前序测试全部依赖"工作区无 agents 目录"隐式假设，改造后逐个炸（晚失败在他人测试里） | 改造点波及的前序测试（PromptBuilderTest/ReActLoopTest/CliAgentConfigurationTest 等）同步补 agent 目录 fixture（011 实录） |

---

## 设计原则

- **底座优先于 Agent**：最重要的交付不是某个强大的 Agent，而是让任意 Agent 可靠运行的环境
- **自实现核心，复用管道**：ReAct 循环手写；LLM 协议适配委托给 Spring AI Alibaba
- **一个目录 = 一个 Agent**：一个业务 Agent 由一个目录定义——`AGENT.md`（frontmatter 配置 + 正文指令）、可选 `skills/` 公共 Skill 软连接与 `scripts/`；Skill 元数据每轮注入，正文/附属资源经 `read_file`/`shell` 按需取用
- **对接开放标准**：工具用 MCP，Agent 目录借 Anthropic Agent Skills 的形态（目录 + 渐进式披露）
- **无状态实例，状态外置**：这是未来走向分布式架构而不需要大改设计的前提
- **安全是地基，不是补丁**：工具来源管控、最小权限、强制沙箱白名单、凭证走环境变量、完整审计记录从第一天就写入 SQLite
- **分阶段克制**：先构建最小完整的运行时内核（五大核心能力）；治理和分布式基础设施在真实使用数据验证后再做
- **锚在需求上，不锚在概念上**：不锚在"Agent OS 这个词"上，锚在"严监管企业需要一个自己能完全掌控的 Agent 底座"这个不变的刚需上
- **手写样板，不引 Lombok**（2026-09-05 拍板）：实体手写 getter，不引入 Lombok——收益仅覆盖极少数实体、DTO/值对象已由 record 原生解决；且阿里规约"谨慎使用 Lombok"、SpotBugs/ErrorProne 兼容成本高、实体"无 setter 收口修改路径"是刻意设计
- **模块即层，子包按领域**（2026-09-05 拍板）：9 模块本身就是 controller/service/dao 分层边界，模块内不再按层分包；子包只按**领域**拆分且仅在类数多的模块（如 axion-tool 的 notify/builtin），小模块保持单包 flat
