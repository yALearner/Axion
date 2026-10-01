# Implementation Plan: ReAct Runtime（第一周：对接 LLM + ReAct 循环）

**Branch**: `001-react-runtime` | **Date**: 2026-08-24 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/001-react-runtime/spec.md`

## Summary

第一周交付 Axion 最小运行时内核：Provider 抽象（显式映射）+ 自实现 ReAct Loop + `http_get` 内置 Tool（带 Sandbox HTTP 域名白名单）+ `axion chat` CLI 多轮对话，并 day-one 落库 `llm_calls` / `tool_invocations` 两张审计表（SQLite）。

技术路径：复用 Spring AI 仅做协议转换与 `@Tool` JSON Schema 生成（禁自动 tool 执行）；`ReActLoop` 自实现同步循环（Java 21 Virtual Thread 承载并发）；CLI 通过非 Web 的 Spring 上下文组装 6 个模块（storage/core/provider/tool/channel-cli/cli），`CliChannel` 汇入 `AgentService.process()`，与未来 Web/Scheduler 入口共用引擎。

## Technical Context

**Language/Version**: Java 21（MUST，virtual thread）

**Primary Dependencies**:
- Spring Boot **3.5.16**（parent，从 3.3.5 升级）；CLI 以非 Web Spring 上下文启动（`SpringApplicationBuilder`，web-application-type NONE）
- Spring AI **1.1.8 GA**（BOM，从 1.0.0-M4 升级）；**移除 Spring AI Alibaba**（无 OpenAI 兼容 starter，DeepSeek/Kimi 走官方 `spring-ai-starter-model-openai`）；`@Tool` + `ToolCallbacks.from()` 生成 schema，`internalToolExecutionEnabled(false)` 禁用内部 tool 执行（详见 research.md R1-GA）
- Picocli 4.7.6（CLI）、SnakeYAML 2.2（AGENT.md frontmatter 解析）
- `net.logstash.logback:logstash-logback-encoder:8.0`（结构化 JSON 日志；Boot 3.5.16 管理 Logback 1.5.34）

**Storage**: SQLite（仅审计两表，Session 内存态）；Spring Data JPA + 手动建表脚本。`hibernate-community-dialects`（Boot BOM 管理 6.6.53.Final，dialect=`org.hibernate.community.dialect.SQLiteDialect`）；`ddl-auto=none` + `spring.sql.init.mode=always` + `schema.sql`（`IF NOT EXISTS` 幂等，EMF 创建前执行）；URL pragma `foreign_keys=on&journal_mode=wal&busy_timeout=5000`，Hikari pool≤4（详见 research.md R2）

**Testing**: JUnit 5 + spring-boot-starter-test；WireMock 3.x（http_get 状态码语义测试）；FakeChatModel（脚本化 LLM 响应，确定性验证 ReAct 循环）；SQLite 测试库 = `jdbc:sqlite::memory:` + `maximum-pool-size=1` + `ddl-auto=create-drop`，另加生产 schema.sql 校验测试（详见 research.md R2）；真实 Provider 多轮抽测为独立 profile（缺 API key 环境变量时跳过）

**Target Platform**: Linux 服务器（JDK 21+，主流发行版优先），桌面系统可用（Windows/macOS 开发）

**Project Type**: Maven 多模块 Java CLI（9 模块骨架已存在，本里程碑填充其中 6 个：axion-storage / axion-core / axion-provider / axion-tool / axion-channel-cli / axion-cli）

**Performance Goals**: SC-005：除 LLM 调用外的单次消息处理内部开销 ≤ 50ms

**Constraints**:
- 全程同步阻塞（不引入 Reactor/WebFlux/CompletableFuture）
- 不使用 SecurityManager；Sandbox 接口先行（`enforce(SandboxAction)`），本里程碑仅 HTTP 域名白名单一档
- API key 仅走环境变量 `${ENV_VAR}`，不明文落配置
- 审计写入失败不得阻断对话主流程（记错误日志继续）
- 白名单 fail-closed：两级均未配置时拒绝一切外网请求

**Scale/Scope**: 单实例多 Agent 并存；Session 内存态（进程结束不恢复）；审计两表每日累积写 SQLite（无查询接口）

**Parent POM 变更清单（实施第一步，依据 research.md）**:
1. `spring-boot-starter-parent` 3.3.5 → 3.5.16
2. `spring-ai.version` 1.0.0-M4 → 1.1.8；移除 `spring-ai-alibaba.version` 与 Alibaba 依赖
3. 删除 `spring-milestones` 仓库（GA 全在 Maven Central）
4. 依赖管理新增 `logstash-logback-encoder:8.0`
5. `sqlite-jdbc` 删除显式 3.45.3.0，跟随 BOM（3.49.1.0）
6. 依赖管理新增 `hibernate-community-dialects`（BOM 管理，无版本）
7. axion-provider 引入 `spring-ai-starter-model-openai`；axion-storage 引入 `spring-boot-starter-data-jpa` + `hibernate-community-dialects` + `sqlite-jdbc`

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| # | 原则 | 本计划遵守方式 | 状态 |
|---|------|---------------|------|
| I | 自实现 ReAct Loop | `ReActLoop` 在 axion-core 手写（循环算法 FR-009），不使用 Spring AI Agent 抽象 / `ChatClient` 自动 tool 执行 | ✅ PASS |
| II | Spring AI 只用两件事 | 仅 ① LLM 协议转换 ② `@Tool` schema 生成；`ToolCallingChatOptions.internalToolExecutionEnabled(false)` 关闭 Spring AI 内部 tool 执行，调度与执行完全由 ReActLoop + ToolExecutor 控制 | ✅ PASS |
| III | Provider 显式映射 | `ProviderService` 维护 `Map<String, ChatModel>` 显式映射，不扫描容器 Bean 类型 | ✅ PASS |
| IV | 一个目录 = 一个 Agent | `AgentLoader.deriveProfile(agentDir)` 派生 Profile；本里程碑不含 Skill（frontmatter 不声明 `skills:`，不违反渐进披露约束） | ✅ PASS |
| V | 审计表 Day One | axion-storage 提供 `LlmCallRepository`/`ToolInvocationRepository`，ReAct 循环内同步写入（FR-022） | ✅ PASS |
| VI | 无 SecurityManager；接口先行 | axion-tool：`Sandbox` 接口（`enforce(SandboxAction)`，`ActionType` 四枚举）+ `WhitelistSandbox` 仅实现 HTTP 域名白名单 | ✅ PASS |
| VII | 同步执行模型 | 全程同步阻塞；并发由 Java 21 Virtual Thread 承载（CLI 单线程交互，Web 阶段启用 spring.threads.virtual） | ✅ PASS |
| VIII | 三种触发源共用一个引擎 | `CliChannel` 是 Channel 实现，消息统一进 `AgentService.process()`；`ReActLoop` 不感知入口 | ✅ PASS |
| IX | Tool 模块三合一 | `HttpTools`（http_get）+ `Sandbox`/`WhitelistSandbox` + `ToolRegistry` 均归 axion-tool；`AGENT.md` 正文加载归 axion-core `ContextLoader` | ✅ PASS |

**Gate 结论**: 9/9 通过，无违规，无需 Complexity Tracking 豁免。

## Project Structure

### Documentation (this feature)

```text
specs/001-react-runtime/
├── plan.md              # This file (/speckit-plan command output)
├── research.md          # Phase 0 output (/speckit-plan command)
├── data-model.md        # Phase 1 output (/speckit-plan command)
├── quickstart.md        # Phase 1 output (/speckit-plan command)
├── contracts/           # Phase 1 output (/speckit-plan command)
├── checklists/requirements.md
└── tasks.md             # Phase 2 output (/speckit-tasks command - NOT created by /speckit-plan)
```

### Source Code (repository root)

9 模块 Maven 骨架已存在（父 POM `com.axion:axion:0.1.0-SNAPSHOT`），本里程碑填充 6 个模块：

```text
axion-core/                       # 核心抽象与循环
└── src/main/java/com/axion/core/
    ├── profile/Profile.java       # AGENT.md frontmatter 派生对象
    ├── session/Session.java, SessionManager.java
    ├── context/ContextLoader.java # AGENT.md 正文注入 system prompt
    ├── loader/AgentLoader.java    # 扫描 .axion/agents/ → deriveProfile
    ├── react/ReActLoop.java, PromptBuilder.java
    ├── tool/AxionTool.java, ToolResult.java, ToolExecutor.java, ToolRegistry.java（统一 Tool 抽象与调度契约）
    ├── agent/AgentService.java    # 三入口共用引擎（CLI/Web/Scheduler 未来汇入）
    └── config/WorkspacePaths.java # .axion/ 路径约定

axion-provider/                   # 能力一：Provider 抽象
└── src/main/java/com/axion/provider/
    ├── ProviderService.java       # Map<String, ChatModel> 显式映射
    └── config/ProviderConfig.java # 构建 ChatModel 的 @Configuration

axion-tool/                       # 能力四（本里程碑仅 http_get + Sandbox）
└── src/main/java/com/axion/tool/
    ├── http/HttpTools.java        # http_get（2xx/4xx/5xx 语义 + 内部重试）；
    │                              #   方法标 @Tool（schema 由 Spring AI 生成，符合宪法 II）
    ├── sandbox/Sandbox.java, SandboxAction.java, ActionType.java
    ├── sandbox/WhitelistSandbox.java  # HTTP 域名白名单（全局+Agent 覆盖语义）
    └── registry/ToolRegistryImpl.java # 用 ToolCallbacks.from(@Tool Beans) 注册 name→ToolCallback

axion-storage/                    # 审计两表（day one）
└── src/main/
    ├── java/com/axion/storage/
    │   ├── entity/LlmCallEntity.java, ToolInvocationEntity.java
    │   └── repo/LlmCallRepository.java, ToolInvocationRepository.java
    └── resources/schema.sql       # 手动建表脚本（IF NOT EXISTS，幂等）

axion-channel-cli/                # CLI Channel
└── src/main/java/com/axion/channel/cli/CliChannel.java

axion-cli/                        # Picocli 入口
└── src/main/java/com/axion/cli/
    ├── AxionCli.java             # 主入口（已存在占位）
    ├── commands/                  # init/status/chat/profile×4/provider list/tool list
    └── config/ConfigLoader.java   # config.yaml + env 占位校验
```

**Structure Decision**: 沿用 CLAUDE.md 定义的 9 模块结构，依赖方向不变：storage ← core ← (provider/tool) ← channel-cli ← cli。CLI 以非 Web Spring 上下文（`SpringApplicationBuilder`）启动，JPA 自动配置负责 storage，`ProviderConfig` 负责 provider Bean；`axion-boot`/`axion-web`/`axion-memory` 本里程碑不动。

## Complexity Tracking

> 无宪法违规，无需豁免记录。
