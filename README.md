<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="./docs/images/axion-logo.svg">
    <img src="./docs/images/axion-logo.svg" alt="Axion Logo" width="440" style="max-width:100%;">
  </picture>
</p>

<p align="center">
  <strong>Java 原生的、企业私有可审计的 Agent 统一底座</strong><br>
  私有部署 · 完全可审计 · 数据不出企业 · 不锁云生态
</p>

<p align="center">
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-Apache%202.0-blue.svg" alt="License"></a>
  <a href="#"><img src="https://img.shields.io/badge/Java-21%2B-orange.svg" alt="Java 21+"></a>
  <a href="#"><img src="https://img.shields.io/badge/Spring%20Boot-3.x-green.svg" alt="Spring Boot 3.x"></a>
  <a href="#"><img src="https://img.shields.io/badge/status-pre--alpha-red.svg" alt="Status: Pre-alpha"></a>
</p>

---

## 什么是 Axion？

**Axion** 是一个开源的、Java 原生的 **Agent OS**（Agent 操作系统）。你把它装在自己的服务器或 K8s 集群上，就能在上面配置、运行、监控多个 AI Agent。所有数据留在你的基础设施里，所有操作可审计，不绑定任何云生态。

> Axion 的名字来源于物理学中的假设粒子**轴子（Axion）**——轻若无物、贯穿万物，被寄望于解开宇宙强 CP 问题的谜底；中文名**枢元**（shū yuán）取「中枢之本」：枢为枢纽，元为根本。一如 Axion 的使命：成为企业 Agent 世界的中枢底座——在严监管、高安全要求的环境中，做一个可靠、可控的 Agent 运行根基。

### 为什么需要 Axion？

今天如果你想在企业里落地 AI Agent，你会面临几个选择：

| 方案 | 问题 |
|------|------|
| **SaaS 平台**（Coze / 扣子） | 数据出企业，合规过不了 |
| **开源 Agent 项目**（OpenClaw / Hermes Agent） | Node.js / Python 生态，跟企业 Java 体系有接缝；企业级治理（RBAC、SSO、审计）是空白 |
| **自己搭框架**（LangChain / Spring AI） | 框架只管 LLM 调用，渠道、记忆、多租户、审计全要自己写 |

Axion 填补的正是这个空白：**Java 生态里、装好就跑、让 Agent 能常驻、可治理、可审计地跑起来的底座**。

### 核心定位：做运行时，不做编排

```
┌──────────────────────────────────────────────┐
│          编排层（Dify / Coze）                 │  ← Axion 不做
│     可视化 Workflow、拖拽编排                  │
├──────────────────────────────────────────────┤
│       Axion · Agent 运行时                   │  ← 我们在这一层
│  Agent 配置 | Channel | Memory | Tool | 审计   │
├──────────────────────────────────────────────┤
│      框架层（Spring AI / LangChain）           │  ← Axion 复用
│      LLM 调用、Prompt 模板、RAG                │
└──────────────────────────────────────────────┘
```

**Axion 做运行时，不做编排。** 复用 Spring AI 调用 LLM，托管 Dify/Coze 做编排，自己聚焦在"让 Agent 在企业里可控地跑起来"这一层。

---

## 特性

**核心阶段（当前）** — Axion 1.0 运行时内核：

- **🤖 Agent 是配置出来的** — 一个目录 = 一个 Agent（`AGENT.md` = frontmatter 配置 + 正文指令），不写 Agent 后端代码
- **🔌 对接主流 LLM** — 基于 Spring AI Alibaba，支持 DeepSeek、通义、Kimi、智谱、混元、豆包等十余个 Provider，运行时切换无 lock-in
- **🧠 ReAct 循环** — 自实现 Agent 大脑，LLM 思考 + 工具执行，多步骤任务自主完成
- **📝 三层记忆** — 会话记忆 + 长期记忆（MEMORY.md），跨对话记住用户偏好和项目背景
- **🔧 Plugin Tool 体系** — 内置 9 个 Tool + 业务方三档扩展（零代码 MCP / 轻代码自写 MCP server / 重代码 @Tool 注解）
- **🌐 REST API** — 10 个核心端点，覆盖会话管理、Agent 调用、信息查询、系统状态
- **⏰ 定时触发** — Agent 按 cron 到点自动运行，不需要人工发起
- **🛡️ 审计 Day One** — `tool_invocations` 和 `llm_calls` 从第一天就写入 SQLite，审计地基立起来
- **☕ Java 21 + Spring Boot 3.x** — 一个 fat JAR 单二进制部署，纳入企业现有 Java 运维体系

**扩展阶段（社区共建）** — 企业级治理层：

- 多租户 RBAC、SSO 单点登录、完整审计与 SIEM 导出
- 多 Channel 接入（企业微信、飞书、钉钉、Slack 等）
- Web 管理台、向量检索（pgvector）、容器级 Sandbox 隔离
- 集群化部署与高可用（Nacos / Sentinel / SkyWalking）

---

## 快速开始

> ⚠️ Axion 当前处于 **pre-alpha** 阶段（核心阶段，单机私有部署），API 可能变动。

### 环境要求

- **Java 21+**
- **Maven 3.8+**

核心阶段只需要 Java + Maven。无需 PostgreSQL、Redis 等外部依赖——数据存储在本地 SQLite。

### 安装与启动

```bash
# 克隆仓库
git clone https://github.com/your-org/axion.git
cd axion

# 编译打包
mvn clean package -DskipTests

# 初始化工作区
java -jar axion-boot/target/axion-boot-*.jar init

# 配置 LLM API Key
export DEEPSEEK_API_KEY=sk-your-key-here

# 启动交互对话
java -jar axion-boot/target/axion-boot-*.jar chat
```

### 配置第一个 Agent

1. 编辑 `.axion/agents/default/AGENT.md`，配置 Provider 和模型
2. （可选）在 `mcp_servers.yaml` 中配置外部 MCP server
3. 运行 `axion chat` 开始对话

```markdown
# .axion/agents/default/AGENT.md
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

---

## 架构概览

<p align="center">
  <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 960 620" font-family="Arial, sans-serif" width="960" style="max-width:100%;">
  <defs>
    <marker id="a" markerWidth="6" markerHeight="5" refX="5" refY="2.5" orient="auto"><path d="M0,0 L6,2.5 L0,5 Z" fill="#64748b"/></marker>
    <marker id="aBlue" markerWidth="6" markerHeight="5" refX="5" refY="2.5" orient="auto"><path d="M0,0 L6,2.5 L0,5 Z" fill="#3b82f6"/></marker>
    <marker id="aGray" markerWidth="6" markerHeight="5" refX="5" refY="2.5" orient="auto"><path d="M0,0 L6,2.5 L0,5 Z" fill="#94a3b8"/></marker>
    <marker id="aRose" markerWidth="6" markerHeight="5" refX="5" refY="2.5" orient="auto"><path d="M0,0 L6,2.5 L0,5 Z" fill="#f43f5e"/></marker>
    <filter id="sh"><feDropShadow dx="0" dy="1" stdDeviation="1.5" flood-opacity="0.08"/></filter>
  </defs>
  <rect width="960" height="620" fill="#f8fafc" rx="6"/>

  <!-- Title -->
  <rect x="0" y="0" width="960" height="36" fill="#1e293b" rx="6"/>
  <rect x="0" y="18" width="960" height="18" fill="#1e293b"/>
  <text x="480" y="23" text-anchor="middle" fill="#f1f5f9" font-size="13" font-weight="bold">Axion 整体架构</text>

  <!-- LEFT: LLM Providers -->
  <rect x="18" y="50" width="140" height="390" rx="8" fill="#f1f5f9" stroke="#cbd5e1" stroke-width="1.2"/>
  <text x="88" y="73" text-anchor="middle" font-size="11" font-weight="bold" fill="#475569">LLM Providers</text>
  <line x1="28" y1="82" x2="148" y2="82" stroke="#e2e8f0" stroke-width="0.8"/>
  <rect x="28" y="92" width="120" height="26" rx="5" fill="#e2e8f0"/>
  <text x="88" y="109" text-anchor="middle" font-size="9" fill="#475569">DeepSeek</text>
  <rect x="28" y="123" width="120" height="26" rx="5" fill="#e2e8f0"/>
  <text x="88" y="140" text-anchor="middle" font-size="9" fill="#475569">通义千问</text>
  <rect x="28" y="154" width="120" height="26" rx="5" fill="#e2e8f0"/>
  <text x="88" y="171" text-anchor="middle" font-size="9" fill="#475569">Kimi</text>
  <rect x="28" y="185" width="120" height="26" rx="5" fill="#e2e8f0"/>
  <text x="88" y="202" text-anchor="middle" font-size="9" fill="#475569">智谱 GLM</text>
  <rect x="28" y="216" width="120" height="26" rx="5" fill="#e2e8f0"/>
  <text x="88" y="233" text-anchor="middle" font-size="9" fill="#475569">混元</text>
  <rect x="28" y="247" width="120" height="26" rx="5" fill="#e2e8f0"/>
  <text x="88" y="264" text-anchor="middle" font-size="9" fill="#475569">豆包</text>
  <text x="88" y="295" text-anchor="middle" font-size="8" fill="#94a3b8">更多 Provider…</text>
  <rect x="28" y="315" width="120" height="46" rx="5" fill="#e2e8f0" stroke="#cbd5e1" stroke-width="0.5"/>
  <text x="88" y="333" text-anchor="middle" font-size="8" fill="#64748b">ProviderService</text>
  <text x="88" y="348" text-anchor="middle" font-size="7" fill="#94a3b8">Map&lt;name, ChatModel&gt;</text>
  <text x="88" y="366" text-anchor="middle" font-size="7" fill="#94a3b8">Spring AI Alibaba 协议转换</text>

  <line x1="158" y1="240" x2="208" y2="240" stroke="#94a3b8" stroke-width="1.8" stroke-dasharray="4,3" marker-end="url(#aGray)"/>

  <!-- RIGHT: MCP + Notify -->
  <rect x="802" y="50" width="140" height="260" rx="8" fill="#f1f5f9" stroke="#cbd5e1" stroke-width="1.2"/>
  <text x="872" y="73" text-anchor="middle" font-size="11" font-weight="bold" fill="#475569">MCP Servers</text>
  <line x1="812" y1="82" x2="932" y2="82" stroke="#e2e8f0" stroke-width="0.8"/>
  <rect x="812" y="92" width="120" height="26" rx="5" fill="#e2e8f0"/>
  <text x="872" y="109" text-anchor="middle" font-size="9" fill="#475569">数据库 MCP</text>
  <rect x="812" y="123" width="120" height="26" rx="5" fill="#e2e8f0"/>
  <text x="872" y="140" text-anchor="middle" font-size="9" fill="#475569">API MCP</text>
  <rect x="812" y="154" width="120" height="26" rx="5" fill="#e2e8f0"/>
  <text x="872" y="171" text-anchor="middle" font-size="9" fill="#475569">文件系统 MCP</text>
  <text x="872" y="205" text-anchor="middle" font-size="8" fill="#94a3b8">更多…</text>
  <rect x="812" y="222" width="120" height="36" rx="5" fill="#e2e8f0" stroke="#cbd5e1" stroke-width="0.5"/>
  <text x="872" y="238" text-anchor="middle" font-size="8" fill="#64748b">McpClientService</text>
  <text x="872" y="252" text-anchor="middle" font-size="7" fill="#94a3b8">JSON-RPC over stdio</text>

  <rect x="812" y="320" width="120" height="70" rx="6" fill="#f1f5f9" stroke="#cbd5e1" stroke-width="1"/>
  <text x="872" y="339" text-anchor="middle" font-size="9" font-weight="bold" fill="#475569">Notify</text>
  <text x="872" y="356" text-anchor="middle" font-size="8" fill="#94a3b8">Webhook</text>
  <text x="872" y="372" text-anchor="middle" font-size="8" fill="#94a3b8">飞书 · 企微 · 钉钉</text>

  <line x1="802" y1="240" x2="752" y2="240" stroke="#94a3b8" stroke-width="1.8" stroke-dasharray="4,3" marker-end="url(#aGray)"/>

  <!-- CENTER: Axion -->
  <rect x="215" y="48" width="530" height="430" rx="10" fill="#ffffff" stroke="#334155" stroke-width="2.5"/>
  <text x="480" y="70" text-anchor="middle" font-size="12" font-weight="bold" fill="#1e293b">Axion · Agent 运行时</text>

  <!-- Band 1: Trigger (Blue) -->
  <rect x="225" y="83" width="510" height="54" rx="6" fill="#eff6ff" stroke="#bfdbfe" stroke-width="1"/>
  <text x="240" y="100" font-size="9" font-weight="bold" fill="#1d4ed8">Trigger · 三种触发源 → 一个统一入口</text>
  <rect x="240" y="108" width="135" height="22" rx="4" fill="#dbeafe"/>
  <text x="307" y="123" text-anchor="middle" font-size="9" fill="#1e40af">💻 CLI · axion chat</text>
  <rect x="382" y="108" width="135" height="22" rx="4" fill="#dbeafe"/>
  <text x="449" y="123" text-anchor="middle" font-size="9" fill="#1e40af">🌐 REST API</text>
  <rect x="524" y="108" width="90" height="22" rx="4" fill="#dbeafe"/>
  <text x="569" y="123" text-anchor="middle" font-size="9" fill="#1e40af">⏰ Scheduler</text>
  <text x="625" y="123" font-size="8" fill="#64748b">人推 · 人推 · 钟推</text>

  <line x1="480" y1="137" x2="480" y2="148" stroke="#3b82f6" stroke-width="1.5" marker-end="url(#aBlue)"/>
  <rect x="390" y="143" width="180" height="16" rx="8" fill="#3b82f6"/>
  <text x="480" y="155" text-anchor="middle" font-size="8" fill="#fff" font-weight="bold">AgentService.process()</text>

  <!-- Band 2: Agent Layer (Rose) -->
  <rect x="225" y="167" width="510" height="50" rx="6" fill="#fff1f2" stroke="#fecdd3" stroke-width="1"/>
  <text x="240" y="184" font-size="9" font-weight="bold" fill="#be123c">Agent Layer · 一个目录 = 一个 Agent（配置出来的）</text>
  <rect x="240" y="192" width="155" height="20" rx="4" fill="#ffe4e6"/>
  <text x="317" y="206" text-anchor="middle" font-size="8" fill="#9f1239">📁 agents/default/AGENT.md</text>
  <rect x="403" y="192" width="90" height="20" rx="4" fill="#ffe4e6"/>
  <text x="448" y="206" text-anchor="middle" font-size="8" fill="#9f1239">Profile</text>
  <rect x="501" y="192" width="115" height="20" rx="4" fill="#ffe4e6"/>
  <text x="558" y="206" text-anchor="middle" font-size="8" fill="#9f1239">Skills (软链接)</text>
  <text x="628" y="206" font-size="8" fill="#64748b">ContextLoader → L1/L2/L3</text>

  <line x1="480" y1="217" x2="480" y2="228" stroke="#f43f5e" stroke-width="1.5" marker-end="url(#aRose)"/>

  <!-- Band 3: Engine (Amber) -->
  <rect x="225" y="232" width="510" height="50" rx="6" fill="#fffbeb" stroke="#fde68a" stroke-width="1"/>
  <text x="240" y="249" font-size="9" font-weight="bold" fill="#b45309">Engine · ReAct Loop（自实现，~数十行 Java）</text>
  <rect x="240" y="256" width="115" height="20" rx="4" fill="#fef3c7"/>
  <text x="297" y="270" text-anchor="middle" font-size="8" fill="#92400e">Reason (LLM 思考)</text>
  <rect x="362" y="256" width="115" height="20" rx="4" fill="#fef3c7"/>
  <text x="419" y="270" text-anchor="middle" font-size="8" fill="#92400e">Act (Tool 执行)</text>
  <rect x="484" y="256" width="115" height="20" rx="4" fill="#fef3c7"/>
  <text x="541" y="270" text-anchor="middle" font-size="8" fill="#92400e">Observe (结果回填)</text>
  <text x="612" y="270" font-size="8" fill="#64748b">PromptBuilder</text>

  <line x1="480" y1="282" x2="480" y2="293" stroke="#f59e0b" stroke-width="1.5" marker-end="url(#a)"/>

  <!-- Band 4: Capability (Purple) -->
  <rect x="225" y="296" width="510" height="66" rx="6" fill="#f5f3ff" stroke="#ddd6fe" stroke-width="1"/>
  <text x="240" y="313" font-size="9" font-weight="bold" fill="#6d28d9">Capability · 五大能力 + 沙箱 + 通知</text>
  <rect x="240" y="320" width="92" height="20" rx="4" fill="#ede9fe"/>
  <text x="286" y="334" text-anchor="middle" font-size="8" fill="#5b21b6">ProviderService</text>
  <rect x="338" y="320" width="92" height="20" rx="4" fill="#ede9fe"/>
  <text x="384" y="334" text-anchor="middle" font-size="8" fill="#5b21b6">MemoryService</text>
  <rect x="436" y="320" width="80" height="20" rx="4" fill="#ede9fe"/>
  <text x="476" y="334" text-anchor="middle" font-size="8" fill="#5b21b6">ToolRegistry</text>
  <rect x="522" y="320" width="80" height="20" rx="4" fill="#ede9fe"/>
  <text x="562" y="334" text-anchor="middle" font-size="8" fill="#5b21b6">Sandbox</text>
  <rect x="608" y="320" width="92" height="20" rx="4" fill="#ede9fe"/>
  <text x="654" y="334" text-anchor="middle" font-size="8" fill="#5b21b6">NotifyTools</text>
  <text x="240" y="354" font-size="7" fill="#8b5cf6">内置 9 Tool · MCP 适配 · 三档扩展 · 接口先行 · llm_calls + tool_invocations 审计</text>

  <line x1="480" y1="362" x2="480" y2="373" stroke="#8b5cf6" stroke-width="1.5" marker-end="url(#a)"/>

  <!-- Band 5: Storage (Green) -->
  <rect x="225" y="378" width="510" height="46" rx="6" fill="#f0fdf4" stroke="#bbf7d0" stroke-width="1"/>
  <text x="240" y="395" font-size="9" font-weight="bold" fill="#15803d">Storage · SQLite + FileSystem（核心阶段）</text>
  <rect x="240" y="402" width="110" height="18" rx="4" fill="#dcfce7"/>
  <text x="295" y="414" text-anchor="middle" font-size="8" fill="#166534">sessions · llm_calls</text>
  <rect x="357" y="402" width="110" height="18" rx="4" fill="#dcfce7"/>
  <text x="412" y="414" text-anchor="middle" font-size="8" fill="#166534">tool_invocations</text>
  <rect x="474" y="402" width="110" height="18" rx="4" fill="#dcfce7"/>
  <text x="529" y="414" text-anchor="middle" font-size="8" fill="#166534">long_term_memory</text>
  <text x="600" y="414" font-size="8" fill="#64748b">.axion/ 文件系统</text>

  <!-- Bottom panel: Module map -->
  <rect x="215" y="432" width="530" height="42" rx="6" fill="#1e293b" opacity="0.85"/>
  <text x="240" y="452" font-size="7" fill="#cbd5e1">axion-core</text><text x="310" y="452" font-size="7" fill="#cbd5e1">axion-provider</text><text x="395" y="452" font-size="7" fill="#cbd5e1">axion-memory</text><text x="480" y="452" font-size="7" fill="#cbd5e1">axion-tool</text><text x="555" y="452" font-size="7" fill="#cbd5e1">axion-channel-cli</text><text x="660" y="452" font-size="7" fill="#cbd5e1">axion-web</text>
  <text x="240" y="467" font-size="6" fill="#94a3b8">核心抽象</text><text x="310" y="467" font-size="6" fill="#94a3b8">Provider 显式映射</text><text x="395" y="467" font-size="6" fill="#94a3b8">Memory 门面</text><text x="480" y="467" font-size="6" fill="#94a3b8">Tool + Sandbox</text><text x="555" y="467" font-size="6" fill="#94a3b8">CLI Channel</text><text x="660" y="467" font-size="6" fill="#94a3b8">REST API</text>

  <!-- LEGEND -->
  <rect x="18" y="455" width="190" height="110" rx="6" fill="#f8fafc" stroke="#e2e8f0" stroke-width="1"/>
  <text x="30" y="474" font-size="9" font-weight="bold" fill="#475569">图例</text>
  <rect x="30" y="483" width="12" height="10" rx="2" fill="#dbeafe" stroke="#bfdbfe" stroke-width="0.5"/>
  <text x="48" y="492" font-size="8" fill="#475569">Trigger / Channel</text>
  <rect x="30" y="499" width="12" height="10" rx="2" fill="#ffe4e6" stroke="#fecdd3" stroke-width="0.5"/>
  <text x="48" y="508" font-size="8" fill="#475569">Agent Layer</text>
  <rect x="30" y="515" width="12" height="10" rx="2" fill="#fef3c7" stroke="#fde68a" stroke-width="0.5"/>
  <text x="48" y="524" font-size="8" fill="#475569">Engine · ReAct</text>
  <rect x="120" y="483" width="12" height="10" rx="2" fill="#ede9fe" stroke="#ddd6fe" stroke-width="0.5"/>
  <text x="138" y="492" font-size="8" fill="#475569">Capability</text>
  <rect x="120" y="499" width="12" height="10" rx="2" fill="#dcfce7" stroke="#bbf7d0" stroke-width="0.5"/>
  <text x="138" y="508" font-size="8" fill="#475569">Storage</text>
  <rect x="120" y="515" width="12" height="10" rx="2" fill="#f1f5f9" stroke="#cbd5e1" stroke-width="0.5"/>
  <text x="138" y="524" font-size="8" fill="#475569">External</text>
  <line x1="30" y1="545" x2="54" y2="545" stroke="#94a3b8" stroke-width="1.5" stroke-dasharray="4,3"/>
  <text x="60" y="549" font-size="8" fill="#475569">跨边界交互</text>
  <text x="30" y="561" font-size="8" fill="#94a3b8">核心阶段 · 单机私有部署</text>

  <!-- Bottom text -->
  <text x="480" y="510" text-anchor="middle" font-size="8" fill="#94a3b8">接口解耦：新增 Channel 或 Tool 只加新模块，不改 axion-core</text>
  <text x="480" y="526" text-anchor="middle" font-size="8" fill="#94a3b8">阶段二引入 PostgreSQL · Redis · Nacos · 分布式的 Agent 互发现与互委托</text>
  </svg>
</p>

**模块结构（9 个 Maven 模块）：**

| 模块 | 职责 |
|------|------|
| `axion-core` | 核心抽象：ReActLoop、PromptBuilder、ToolExecutor、AgentService、AgentScheduler |
| `axion-provider` | LLM Provider 抽象，多 Provider 显式映射 |
| `axion-memory` | MemoryService 统一门面，LongTermMemory 可插拔后端 |
| `axion-tool` | 内置 Tool、MCP Client、ToolRegistry、Sandbox、NotifyTools |
| `axion-channel-cli` | CLI Channel 交互式对话 |
| `axion-web` | REST API（10 个端点）、GlobalExceptionHandler、OpenAPI |
| `axion-storage` | SQLite 持久化、审计表写入 |
| `axion-cli` | Picocli 命令行入口（12 个子命令）、ConfigLoader |
| `axion-boot` | Spring Boot 启动模块、自动配置、依赖聚合 |

---

## 技术栈

| 层面 | 技术 | 说明 |
|------|------|------|
| 语言 | Java 21+ | Virtual Thread 处理高并发 |
| 框架 | Spring Boot 3.x | 企业后端事实标准 |
| LLM 调用 | Spring AI + Spring AI Alibaba | DeepSeek、通义、Kimi 等十余个 connector |
| Tool 协议 | MCP（Model Context Protocol） | Anthropic 开放标准，Agent 生态事实标准 |
| 持久化 | SQLite + Spring Data JPA | Session、审计、元数据（核心阶段） |
| CLI | Picocli | 12 个子命令 |
| 日志 | Logback + SLF4J | 结构化 JSON 日志 |
| 构建 | Maven | 多模块项目 |

扩展阶段引入：PostgreSQL + pgvector（向量检索）、Redis（会话缓存）、Nacos（服务发现）、Sentinel（限流熔断）、SkyWalking（链路追踪）、Prometheus + Grafana（监控）。

---

## 路线图

| 阶段 | 形态 | 重点 | 状态 |
|------|------|------|------|
| **阶段一** | 单机私有部署 | 完整运行时内核（五大核心能力），把单机做扎实 | 🚧 开发中 |
| **阶段二** | 底座分布式部署 | 多实例 + 外置状态，高可用，水平扩展 | 📋 规划中 |
| **阶段三** | 分布式 Agent 协作 | 跨节点/跨组织 Agent 互发现、互委托 | 💡 远期愿景 |

---

## 与同类项目的对比

| 维度 | Axion | OpenClaw | Hermes Agent |
|------|--------|----------|-------------|
| 语言生态 | **Java / Spring** | Node.js | Python |
| 定位 | **严监管企业** | 个人 / 小团队 | 个人到团队 |
| Agent 定义方式 | 一个目录 = 一个 Agent（AGENT.md） | SKILL.md | SKILL.md |
| LLM 调用 | Spring AI Alibaba | 自实现 | 自实现 |
| Tool 协议 | MCP | MCP / 自研 | MCP |
| 多租户 RBAC | 🔜 扩展阶段 | ❌ | ❌ |
| 全链路审计 | ✅ Day One 写入 | ❌ | ❌ |
| 企业 IM 渠道 | 🔜 扩展阶段 | ⚠️ 社区扩展 | ⚠️ 社区扩展 |
| 沙箱隔离 | ✅ WhitelistSandbox（核心阶段） | ❌ 可选 | ⚠️ 部分 |
| 部署形态 | 一个 fat JAR | Node.js 服务 | Python 服务 |

> Axion 跟 OpenClaw、Hermes Agent 是**同类不同定位**——三者都采用 markdown + frontmatter 的目录形态定义 Agent，社区的优质 Skill 经企业审查后可跨项目复用。

Axion 跟 Dify/Coze（编排平台）是**互补关系**——Dify 可跑在 Axion 之上作为编排层，Axion 做底层运行时。

---

## 文档

| 文档 | 说明 |
|------|------|
| [行业调研](./docs/IndustryResearch.md) | Agent OS 业界格局、Java 生态缺位与 Axion 定位 |
| [需求分析](./docs/DemandAnalysis.md) | 功能需求、五大核心能力、验收标准 |
| [技术方案](./docs/TechnicalSolution.md) | 技术选型、系统架构、9 个模块设计 |
| [AI 编程指南](./docs/AiProgrammingGuide.md) | Spec-Kit 工作流、项目宪章、常见陷阱 |
| [CLAUDE.md](./CLAUDE.md) | Claude Code 项目上下文（模块结构、Constitution、API 参考） |

---

## 贡献

Axion 处于早期阶段，欢迎一切形式的贡献：代码、文档、Issue、讨论。

1. Fork 本仓库
2. 创建特性分支 (`git checkout -b feat/amazing-feature`)
3. 提交变更 (`git commit -m 'feat: add amazing feature'`)
4. 推送到分支 (`git push origin feat/amazing-feature`)
5. 创建 Pull Request

> 提交前请阅读 [CLAUDE.md](./CLAUDE.md) 了解项目宪章（Constitution）和常见陷阱，确保代码符合不可违背的编码原则。

### 开发环境搭建

```bash
# 需要 Java 21+ 和 Maven 3.8+
mvn clean compile
mvn test
```

---

## 许可证

本项目采用 [Apache License 2.0](LICENSE)。

---

## 致谢

Axion 的设计深受以下项目的启发：

- [OpenClaw](https://github.com/openclaw/openclaw) — 消费者级 Agent OS 先驱，30 万+ GitHub stars
- [Hermes Agent](https://github.com/NousResearch/hermes-agent) — 工程级 Agent OS 标杆
- [Spring AI](https://spring.io/projects/spring-ai) — Java AI 工程基石
- [Spring AI Alibaba](https://java2ai.com) — 十余个主流 LLM connector
- [Model Context Protocol](https://modelcontextprotocol.io) — Agent-Tool 交互开放标准
