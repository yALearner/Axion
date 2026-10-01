# Implementation Plan: 011-plugin-agent 插件化 Agent（一个目录定义一个会自己跑的 Agent）

**Branch**: `011-plugin-agent` | **Date**: 2026-09-14 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/011-plugin-agent/spec.md`（S1+S2 已完成，1 题澄清已写回）

## Summary

把一个目录变成"一个会自己跑的 Agent"：`ProfileLoader` 更名 `AgentLoader` 并扩职责（拆 frontmatter/正文 + 认资源 + name=目录名校验）；`ContextLoader` 注入 AGENT.md 正文（现读无缓存）+ Skill 元数据改注入**绑定路径**；`ProfileRegistry` 补 remove/exists、`AgentScheduler` 抽 registerProfile（30 节注销/更新前置）；L3 脚本沙箱（新配置键 `shell.allowed_interpreters` 双白名单 + 简单形态钉死 + scripts/ 目录限定 + 解释器命令 cwd=Agent 目录）；FILE_READ 动态白名单 = 当前 Agent 目录（未绑定不可见）；daily-reconcile 示例 Agent（四文件 + 公共技能实体 + 软连接绑定）。**零新表、零新模块、零新第三方依赖**——纯增量 + 6 处经拍板的改造点。

技术决策全文见 [research.md](./research.md)（R1~R10），数据模型见 [data-model.md](./data-model.md)，跨节契约见 [contracts/agent-runtime.md](./contracts/agent-runtime.md)，验证入口见 [quickstart.md](./quickstart.md)。

## Technical Context

**Language/Version**: Java 21（virtual thread）；Spring Boot 3.x；Maven 多模块

**Primary Dependencies**: 零新增——SnakeYAML（AgentLoader frontmatter 解析，既有）、JUnit 5 + Mockito + AssertJ（既有）、ThreadPoolTaskScheduler（既有）、Spring Boot ConfigurationProperties（ShellSandboxProperties 增字段）

**Storage**: 无新表——SQLite 六表（sessions/tool_invocations/llm_calls/notify_channels/scheduled_tasks/task_executions）原样；示例 Agent 与技能实体是文件系统资源（测试 resources + 运行时 `.axion/` 工作区）

**Testing**: JUnit 5 + Mockito + AssertJ（既有）；临时目录 fixture + 程序建软连接/junction（ContextLoaderTest 既有模式）；真实 ThreadPoolTaskScheduler + mock store（AgentSchedulerTest 既有模式）；六 harness 测试类 + WhitelistSandboxTest 扩展

**Target Platform**: 企业服务器（Linux 生产：bash/python 在 PATH）/ 本机 Windows dev（junction 特权）

**Project Type**: Java 多模块 Spring Boot Web 服务（9 模块，本节动 core / tool / cli / boot 四模块）

**Performance Goals**: 无新性能目标；正文/元数据每轮现读（小文件 I/O，坑三铁律优先于缓存收益）

**Constraints**: 全程同步阻塞（宪法 VII）；底座零重写（只改 Profile 来源）；正文即时生效（无缓存）；解释器命令简单形态（坑九 + 脚本参数透传）；信任边界诚实标注；不新增配置键之外的对外概念；不改 9 模块结构

**Scale/Scope**: 单实例；Agent 数 = `.axion/agents/` 子目录数（个位~几十）；每轮 prompt 组装多读 1 个 AGENT.md 文件；无新性能风险

## Constitution Check

*GATE: 进入 Phase 0 前已过；Phase 1 设计后复核。*

| # | 原则 | 本节如何满足 | 复核 |
|---|------|-------------|------|
| I | 自实现 ReAct Loop | 零新循环代码——触发链路复用 AgentService.process → 既有 ReActLoop | ✅ |
| II | Spring AI 只用两件事 | 本节不碰 LLM 调用层；无 ChatClient 自动 tool 执行路径新增 | ✅ |
| III | Provider 显式映射 | 不新增 Provider；deriveProfile 的 provider 引用校验沿用 001 纪律 | ✅ |
| IV | 一个目录 = 一个 Agent | **本节主体**——AgentLoader 派生 + skills/ 软连接绑定视图 + 三层渐进披露（正文常驻/子指令按需/未绑定不可见）；frontmatter 无 skills 字段 | ✅ |
| V | 审计 Day One | 无新审计面——read_file/shell 走既有 ToolExecutor 审计路径（成功失败都落 tool_invocations）；本节不改审计机制 | ✅ |
| VI | 无 SecurityManager | Sandbox 接口 `enforce(SandboxAction)` 零改动；WhitelistSandbox 内部演进（workspaceRoot + 解释器双白名单 + FILE_READ 动态根） | ✅ |
| VII | 同步执行模型 | 全程同步；无 Reactor/CompletableFuture 新增 | ✅ |
| VIII | 三种触发源共用一个引擎 | schedules 从 Agent 目录 frontmatter 派生进 Profile → AgentScheduler 照旧注册（008 零改动）；到点走同一 process | ✅ |
| IX | Tool 模块三合一 | AgentLoader/ContextLoader 在 core（Agent 目录不是 Tool）；沙箱改造在 axion-tool 内；不新增模块 | ✅ |

## Project Structure

### Documentation (this feature)

```text
specs/011-plugin-agent/
├── plan.md              # 本文件
├── research.md          # Phase 0：R1~R10 技术决策
├── data-model.md        # Phase 1：目录结构 + 校验规则状态机 + 配置键 schema
├── quickstart.md        # Phase 1：验证指南（机器判卷 + 人工项）
├── contracts/
│   └── agent-runtime.md # Phase 1：30 节消费的运行时注册/沙箱/绑定路径契约
├── checklists/
│   └── requirements.md  # S1 质量清单
├── flow-status.md
├── spec.md
└── tasks.md             # Phase 2 输出（/speckit-tasks，非本命令创建）
```

### Source Code (repository root)

```text
axion-core/src/main/java/com/axion/core/
├── AgentLoader.java        # ProfileLoader 更名 + 扩职责（split/loadBody/detectResources/deriveProfile/loadAll）
├── ContextLoader.java      # 正文注入 + 绑定路径元数据（002 演进）
├── ProfileRegistry.java    # 补 remove/exists（001 演进）
└── AgentScheduler.java     # 抽 registerProfile（008/010 演进）
axion-core/src/test/java/com/axion/core/
├── AgentLoaderTest.java    # ProfileLoaderTest 更名扩展
├── DeriveProfileTest.java
├── AgentScanRegisterTest.java
├── ProfileRegistryRuntimeTest.java
└── AgentSchedulerRegisterTest.java
axion-core/src/test/resources/
├── agents/daily-reconcile/   # 示例 Agent 四文件（AGENT.md/REFERENCE.md/scripts/skills 绑定）
└── skills/report-format/     # 公共技能实体 SKILL.md
axion-tool/src/main/java/com/axion/tool/
├── WhitelistSandbox.java     # workspaceRoot + 解释器双白名单 + FILE_READ 动态根（007 演进）
├── ShellSandboxProperties.java  # 增 allowedInterpreters 字段
└── builtin/ShellTools.java   # 增解释器集合 + workspaceRoot + cwd=Agent 目录（005 演进）
axion-tool/src/test/java/com/axion/tool/
├── WhitelistSandboxTest.java        # 双白名单/子集 WARN + FILE_READ 动态根（坑七）扩展
├── ShellToolsTest.java              # 解释器 cwd 回归（坑六，既有类扩展）
└── ProgressiveDisclosureTest.java   # S5 F1 修正落位：正文/绑定路径/渐进披露守点（tool→core 依赖方向合法，消费 core 类 + tool 沙箱类）
axion-cli/src/main/java/com/axion/cli/CliAgentConfiguration.java   # 装配适配
axion-boot/src/main/resources/application.yaml                      # allowed_commands 增补 + allowed_interpreters 新键
```

**Structure Decision**: 不新增模块/目录结构——全部改动落在既有 9 模块的既有包内（core 四类、tool 三类 + 三测试、cli 装配、boot 配置）；示例 Agent 与技能实体落 axion-core 测试资源（git 可提交；junction 无法提交，绑定由测试程序创建，ContextLoaderTest 既有模式）。ProgressiveDisclosureTest 落 axion-tool 测试（S5 F1 修正：内容需 WhitelistSandbox/ShellTools，core 不得反向依赖 tool）。

## Complexity Tracking

无违反项——宪法九条全部满足（上表），无需复杂性豁免。
