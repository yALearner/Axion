<!--
  Sync Impact Report
  ==================
  Version change: (unversioned scaffold) → 1.0.0
  Rationale: 初次正式批准（initial ratification）——原文件为未填充的模板脚手架，无版本号；
    本次按 CLAUDE.md「不可违背的原则」与 AiProgrammingGuide §1.2 的 constitution 来源指引正式成文。
  Modified principles: 无（全部为新增）
  Added sections: Core Principles（9 条）、技术约束与安全要求、开发工作流、Governance
  Removed sections: 无
  Follow-up TODOs: 无
-->

# Axion Constitution

Axion 是面向企业场景的 Agent OS（Java 实现）。本宪法是项目最高治理文件，优先级高于
CLAUDE.md、docs/ 下的需求与技术方案、以及任何实现层面的习惯。所有代码、Spec、PR 必须遵守。

> 来源：`CLAUDE.md`「不可违背的原则」、`docs/AiProgrammingGuide.md` §1.2（constitution 输入 =
> 需求文档第 3 章设计目标 + 技术方案第 1.1 节关键技术决策）。

## Core Principles

### I. 自实现 ReAct Loop

`ReActLoop` MUST 自己实现，MUST NOT 使用 Spring AI 的 Agent 抽象（如
`ChatClient.prompt().call()` 的自动工具执行）。核心循环约数十行 Java，完整掌握 Agent
工作机制，保留未来定制循环行为的空间。

### II. Spring AI 只用两件事

Spring AI 在 Axion 里只做：① LLM Provider 协议转换；② `@Tool` 注解的 JSON Schema 生成。
MUST 禁用 Spring AI 的自动 tool 执行——Tool 的调度和执行完全由 `ReActLoop` + `ToolExecutor`
控制。违反此原则会导致 tool 被调两次。

### III. Provider 显式映射

多 Provider 并存时 MUST 维护 `provider name → ChatModel` 的显式映射表，MUST NOT 靠扫描
Spring 容器里的 `ChatModel` Bean 类型区分 Provider（Bean 类型相同、Bean name 未必等于
provider name）。

### IV. 一个目录 = 一个 Agent；Skill 以本地软连接绑定并渐进披露

一个目录 = 一个 Agent：`.axion/agents/<name>/` 里 `AGENT.md` = frontmatter（运行配置）+
正文（任务指令），外加可选 `skills/`（Skill 绑定视图）、`scripts/`、`REFERENCE.md`。
`AgentLoader.deriveProfile(agentDir)` 把 frontmatter 派生成底座认识的 `Profile`。

公共 Skill 实体统一存放在 `.axion/skills/<name>/`。Agent 可见的 Skill 只由
`.axion/agents/<agent>/skills/<name>` 下指向公共实体的**相对软连接**表达；软连接集合是
唯一绑定真相源，`AGENT.md` frontmatter MUST NOT 声明 `skills:`。

加载走三层渐进式披露：每轮 prompt 只注入当前 Agent 已绑定 Skill 的 name + description +
本地绝对读取路径；模型命中后用 `read_file` 读取 `SKILL.md` 正文；Skill 附属参考/脚本继续
按需读取或运行。MUST NOT 预载正文、MUST NOT 新增 `use_skill`，Skill MUST NOT 进
`ToolRegistry`。

### V. 审计表 Day One 写入

`tool_invocations` 和 `llm_calls` 两张审计表核心阶段 MUST 写入（不需要查询接口，但写入
不能省）。MUST NOT 以"日志够了"为由跳过落库——可审计是 Axion 的核心差异化能力。

### VI. 不使用 Java SecurityManager

`SecurityManager` 在 JDK 17 起废弃、JDK 21 已不可用，MUST NOT 使用。Sandbox 通过**接口先行**
实现——`Sandbox` 接口只有一个方法 `enforce(SandboxAction action)`（`ActionType` =
`FILE_READ | FILE_WRITE | SHELL_COMMAND | HTTP_REQUEST`），不携带任何实现细节（不出现
白名单/容器/VM 字样）。核心阶段 `WhitelistSandbox` 按三层白名单校验：文件路径白名单
（需处理 `../` 路径穿越）、Shell 命令首 token 白名单、HTTP 域名通配符白名单。扩展阶段按
信号驱动升级到容器/microVM，接口不变，只新增实现类。

### VII. 同步执行模型

核心阶段全程同步阻塞，配合 Java 21 Virtual Thread 处理并发。MUST NOT 引入 Reactor /
WebFlux / CompletableFuture 等异步编程模型（SSE 流式响应放扩展阶段）。

### VIII. 三种触发源共用一个引擎

CLI（人推）、Web Service（人推）、`AgentScheduler`（钟推）三个入口最终都 MUST 汇入同一个
`AgentService.process()`，`ReActLoop` 不感知消息从哪个入口来。钟推的 Session 中 channel 和
user 都固定为 `scheduler`。

### IX. Tool 模块三合一

内置 Tool、MCP Client、Sandbox、NotifyTools MUST 合并在一个 `axion-tool` 模块，不拆成多个
模块。`AGENT.md` 正文加载归 `axion-core` 的 `ContextLoader`（Agent 目录不是 Tool）。

## 技术约束与安全要求

- **运行时**：Java 21（MUST，virtual thread 处理并发）、Spring Boot 3.x、Spring MVC；
  LLM 调用复用 Spring AI Alibaba（受原则二约束）。
- **构建与模块**：Maven 多模块，9 个模块按 CLAUDE.md 定义；模块之间通过接口解耦，新增
  Channel 或 Tool 只加新模块，MUST NOT 改 `axion-core`。
- **持久化**：SQLite + Spring Data JPA（核心阶段）。SQLite 上 `hibernate.ddl-auto=update`
  的 ALTER TABLE 支持很弱，表结构变更 MUST NOT 依赖 Hibernate 自动迁移——手动维护建表脚本
  或引入 Flyway。
- **配置与密钥**：敏感配置（API key、MCP 凭证）通过环境变量注入（`${ENV_VAR}` 占位），
  MUST NOT 明文写在 Profile YAML；`ConfigLoader` 启动时做必填项与格式校验，缺失或非法时
  给清晰报错，不静默失败。
- **日志**：Logback + SLF4J，结构化 JSON。
- **核心阶段明确不做**：认证（假设内网）、SSE 流式、WebSocket、限流、RBAC、Provider
  fallback/hedge racing、Tool 调用并行、Agent 间任务委托——均放扩展阶段。
- **信任边界**：安装带脚本的 Agent = 信任该 Agent 作者；`shell` 跑脚本会绕过 HTTP 域名
  白名单，白名单只管解释器 + 脚本目录。文档 MUST 对这条诚实说明。

## 开发工作流

- **Spec-Kit 驱动**：主体开发按 specify → clarify → plan → tasks → implement 流程；
  增量开发（扩展功能、修 bug）用手动提示词配合 Claude Code。
- **User Story 拆解与依赖顺序**：五大核心能力 = 5 个 user story，推进顺序
  US-1（对接 LLM）→ US-2（ReAct）→（US-3 Memory ∥ US-4 Tool）→ US-5（Web Service）；
  plan 的模块结构 MUST 与技术方案第 10 章的 9 个模块一致。
- **节奏**：核心阶段 4 周、每周 3 小时；每周末有可演示成果，两个验收 Demo 跑通是核心功能
  发布的硬条件。
- **质量门**：需求验收（DemandAnalysis 第 13 章）为 acceptance criteria 来源；每个功能模块
  MUST 至少有一个端到端测试用例覆盖；评审时核对本宪法 9 条原则。

## Governance

- **优先级**：本宪法 supersede 一切其他实践与文档；冲突时以宪法为准，并同步修正冲突文档。
- **修订流程**：修订 MUST 经文档化（在 docs/ 或 CLAUDE.md 同步更新）、评审、迁移计划三步；
  原则的删除或重新定义属于 MAJOR 版本变更。
- **版本策略**：语义化版本——MAJOR（原则删除/重定义）、MINOR（新增原则/章节或实质性扩充）、
  PATCH（措辞澄清、非语义修正）；`LAST_AMENDED_DATE` 在每次修订时更新为修订日。
- **合规审查**：所有 PR/评审 MUST 验证对 9 条原则的遵守；复杂度 MUST 有正当理由；
  运行时开发指引以 `docs/AiProgrammingGuide.md` 为准。

**Version**: 1.0.0 | **Ratified**: 2026-08-24 | **Last Amended**: 2026-08-24
