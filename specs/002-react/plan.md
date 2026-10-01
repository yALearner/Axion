# Implementation Plan: ReAct 循环（Agent 大脑）

**Branch**: `002-react` | **Date**: 2026-09-01 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/002-react/spec.md`

## Summary

在 001（Provider 对接 LLM）之上交付 ReAct 循环——Axion 最关键的一段代码：`ReActLoop` 手写主循环（想一步、做一步、看结果，直到无工具调用或转满最大轮数），`PromptBuilder` 组装每轮四段上下文，`ToolExecutor` 唯一执行点 + `tool_invocations` 审计 day one 落库，`AgentService` 三触发源共用编排入口 + `ProfileContext` 防 ThreadLocal 泄漏。同时交付两块拍板补位：`Session`/`SessionManager` 最小契约（内存版）与 `Sandbox` 纯接口（落 axion-tool）。宪法原则 I（自实现）、II（禁自动执行）、V（审计 day one）、VII（同步）、VIII（共用引擎）五条硬约束，坑一~坑八每个都有回归测试钉死。全部不碰网络，harness 全单测秒级跑完。

## Technical Context

**Language/Version**: Java 21 + Spring Boot 3.x，Maven 多模块（BOM 已锁定：spring-ai-bom 1.1.8、sqlite-jdbc 3.53.4.0）

**Primary Dependencies**: Spring AI 数据模型（core 引入，拍板③：可用其纯数据模型 Prompt/ChatResponse/ToolCall/ToolDefinition，禁用 Agent 抽象与自动 tool 执行——确切 artifact 以 H3 实测核实为准）；Spring Data JPA + SQLite（沿用 001）；JUnit 5 + Mockito（001 测试栈）

**Storage**: SQLite `.axion/axion.db`；`tool_invocations` 手工 schema.sql **增量追加**（与 001 `llm_calls` 同口径：手工建表、测试执行同一份脚本、created_at ISO-8601 TEXT），不依赖 `hibernate.ddl-auto=update`

**Testing**: 7 个测试类全单测（模型/工具/文件系统全 mock 或临时目录），`mvn test` 秒级；无集成冒烟（本节不碰网络）；完成定义 = `mvn clean verify` 全绿（含静态门禁）

**Target Platform**: 企业服务器 / 本地单节点（Windows/Linux），JDK 21

**Project Type**: Maven 多模块 Agent 底座运行时（本 feature 为 axion-core 第二批核心代码 + axion-tool 首批内容 + axion-storage 审计表扩展）

**Performance Goals**: 节级无硬指标；同步阻塞 + 虚拟线程承载并发（宪法 VII）

**Constraints**: 宪法 9 条（重点 I/II/V/VII/VIII）；模块结构 MUST 与技术方案第 10 章 9 模块一致（只触碰 axion-core / axion-tool / axion-storage）；依赖方向 MUST 保持 core ← 能力层（core 不反向依赖 provider/tool）；全程不自动 commit/push/package.sh

**Scale/Scope**: 单实例多 Agent 并存；本轮触发源接入从第 18 节（CLI）开始，本节交付编排者本体

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 原则 | 本节判定 | 依据 |
|------|---------|------|
| I 自实现 ReAct Loop | ✅ PASS（硬约束） | `ReActLoop` 手写约数十行，不触发 Spring AI Agent 抽象；坑六架构断言回归钉死 |
| II Spring AI 只用两件事 | ✅ PASS（硬约束） | 协议转换在 001（ProviderService）；工具 schema 翻译 = ToolSchemaAdapter（G2 拍板迁往 core，PromptBuilder 直接复用）；自动执行关闭由 001 防线 + 本节回归守护 |
| III Provider 显式映射 | ✅ 不涉及（N/A） | Provider 路由在 001 已钉死，本节只消费 |
| IV 一个目录 = 一个 Agent；软连接 | ✅ PASS（部分涉及） | `ContextLoader` 按软连接集合读 Skill 元数据（name/description），frontmatter 不声明 skills；AGENT.md 正文注入归第 29 节 |
| V 审计表 Day One 写入 | ✅ PASS（硬约束） | `tool_invocations` 成功失败都落（success/error_message 与 001 llm_calls 同口径）；llm_calls 由 001 继续负责，循环传 session.id() 关联 |
| VI 不使用 SecurityManager | ✅ PASS | 只交付 `Sandbox` 纯接口（enforce 单方法 + ActionType 四值 + 违规异常），零实现、无白名单配置 |
| VII 同步执行模型 | ✅ PASS（硬约束） | 全程同步阻塞，无 Reactor/CompletableFuture |
| VIII 三种触发源共用一个引擎 | ✅ PASS（硬约束） | `AgentService.process` 统一编排 + `ProfileContext` finally 清理；触发源接入第 18 节起，ReActLoop 不感知入口 |
| IX Tool 模块三合一 | ✅ PASS | Sandbox 接口落 axion-tool（三合一）；不新建模块；Agent 目录加载归 core 的 ContextLoader |
| 技术约束：建表/虚拟线程/日志 | ✅ PASS | 手工 schema.sql 增量；同步+虚拟线程；结构化 JSON 日志（地基已有） |
| 质量门：每模块端到端测试 | ✅ PASS | 7 个测试类覆盖三个模块（坑↔测试对号表见需求文档验收标准） |

无违反项，Complexity Tracking 不适用。两个跨模块依赖摩擦点（依赖方向 vs 001 交付物消费）列入 G2 人工确认项（见 research.md R2/R3）。

## Project Structure

### Documentation (this feature)

```text
specs/002-react/
├── plan.md              # 本文件（/speckit-plan 输出）
├── research.md          # Phase 0 输出
├── data-model.md        # Phase 1 输出
├── quickstart.md        # Phase 1 输出
├── contracts/           # Phase 1 输出（react-loop.md / session-manager.md / sandbox.md）
├── checklists/          # 质量清单
├── flow-status.md       # axion-spec 进度文件
└── tasks.md             # Phase 2 输出（/speckit-tasks，本阶段不创建）
```

### Source Code (repository root)

```text
axion-core/
├── src/main/java/com/axion/core/        # 新增：ReActLoop、PromptBuilder、ToolExecutor、
│                                          #   AgentService、ProfileContext、ContextLoader、
│                                          #   Session、SessionManager、LlmGateway（端口接口）
│                                          # 迁入：ToolSchemaAdapter（自 provider，G2 拍板）
├── src/test/java/                        # ReActLoopTest、PromptBuilderTest、ToolExecutorTest、
│                                          #   AgentServiceTest、ContextLoaderTest、SessionManagerTest
└── pom.xml                               # + spring-ai 数据模型依赖（拍板③，artifact 以 H3 实测为准）
axion-tool/
└── src/main/java/com/axion/tool/        # 首批内容：Sandbox、SandboxAction、ActionType、
                                          #   SandboxViolationException（纯接口墙，零实现）
axion-storage/
├── src/main/java/com/axion/storage/     # 新增：ToolInvocation 实体、ToolInvocationRepository
├── src/main/resources/schema.sql         # 增量追加 tool_invocations DDL（手工建表）
└── src/test/java/                        # ToolInvocationRepositoryTest（执行同一份 schema.sql）
axion-provider/                          # G2 拍板：ToolSchemaAdapter + 其测试迁往 core；
                                          #   ProviderService 加 implements LlmGateway（一行声明）
```

**Structure Decision**: 沿用既有 9 模块 Maven 结构，本 feature 只触碰上述模块。依赖方向铁律：core ← provider/tool ← 组装层；core → storage（ToolExecutor 依赖 ToolInvocationRepository，001 先例 ProviderService → LlmCallRepository 同向）。两个跨模块消费点经 G2 拍板定稿：ReActLoop 经 core 端口 `LlmGateway` 调 LLM（ProviderService 加 implements）；ToolSchemaAdapter 迁往 core 供 PromptBuilder 复用（research.md R2/R3）。
