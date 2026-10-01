# Implementation Plan: CLI 命令行入口与 Session 持久化

**Branch**: `003-cli` | **Date**: 2026-09-02 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/003-cli/spec.md`

## Summary

在 001（Provider）+ 002（ReAct 引擎）之上交付第一个"看得见摸得着"的入口：`AxionCli` 挂接 12 个 `@Command` 子命令（Picocli），轻命令不起 Spring 秒回、重命令（chat/serve/gateway）才启动 Spring（`AxionApplication`，坑九的 JPA 扫描根已由 002 fix 显式声明）；`CliChannel` 读—转交—打印的 chat 交互；`SessionEntity` + `SessionRepository` + `sessions` 表（手工 schema.sql 增量）落地 Session 持久化；`SessionManager` 按 002 跨节契约**换 JPA 实现、契约不变**（进程内缓存 + 落库双真相）。宪法 VIII（三触发源共用 AgentService）在此首次接线（CLI 第一个入口）、V（审计 day one 延续）、IV（Agent 目录真相源）。坑九有架构断言回归钉死。

## Technical Context

**Language/Version**: Java 21 + Spring Boot 3.x，Maven 多模块（BOM 已锁定：spring-ai-bom 1.1.8、sqlite-jdbc 3.53.4.0、Picocli 4.7.6）

**Primary Dependencies**: Picocli（命令行，地基已锁）、Spring Boot（仅重命令启动）、Spring Data JPA + SQLite（sessions 表，手工建表脚本同口径）、Jackson（core 已有，Message 序列化）、SnakeYAML（轻命令读配置，core 传递）

**Storage**: SQLite `.axion/axion.db`；`sessions` 表 schema.sql **增量追加**（`messages_json` 整体序列化一列存；`created_at`/`last_active_at`/`archived_at` ISO-8601 TEXT 复用 `InstantTextConverter`）；`ddl-auto: none`

**Testing**: 2 个会话层测试类全单测不碰网络（课件 §四 harness）：`SessionManagerTest`（改造为 mock `SessionRepository` 单测）+ `SessionRepositoryTest`（真 SQLite 临时库 + 手工 schema.sql + 模拟重启）；外加 1 个坑九架构断言测试类 `JpaScanConfigurationTest`（axion-cli）；命令分流/`--help` 属进程级行为入人工清单（课件明确）；完成定义 = `mvn clean verify` 全绿

**Target Platform**: 企业服务器 / 本地单节点（Windows/Linux），JDK 21

**Project Type**: Maven 多模块 Agent 底座运行时（本 feature 为 axion-cli 首批命令 + axion-channel-cli 首批内容 + axion-storage sessions 表 + axion-core SessionManager 实现改造）

**Performance Goals**: 轻命令秒级返回（不起 Spring，人工验证）；重命令启动 2~4 秒内（Spring 启动固有成本）；无硬性延迟指标

**Constraints**: 宪法 9 条（重点 VIII/IV/V/VII）；模块结构 MUST 与技术方案第 10 章 9 模块一致（只触碰 axion-cli / axion-channel-cli / axion-storage / axion-core）；`SessionManager` 只换实现不改签名（002 跨节契约）；全程不自动 commit/push/package.sh

**Scale/Scope**: 单实例多 Agent 并存；12 子命令全部注册；serve/gateway 本课占位（Web 本体归第 26 节）

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 原则 | 本节判定 | 依据 |
|------|---------|------|
| I 自实现 ReAct Loop | ✅ 不涉及（N/A） | 引擎在 002 已交付，本节只接线 |
| II Spring AI 只用两件事 | ✅ 不涉及（N/A） | 本节无 LLM 新代码；调用仍走 ProviderService |
| III Provider 显式映射 | ✅ 不涉及（N/A） | 001 已钉死 |
| IV 一个目录 = 一个 Agent | ✅ PASS（硬约束） | profile 四命令以 `.axion/agents/` 目录为真相源（课件 profiles/ 表述冲突经用户拍板按宪法 IV）；chat 按 profile 名路由 |
| V 审计表 Day One 写入 | ✅ PASS（延续） | 审计路径全在 002 引擎内，本节不新增旁路；坑九回归保证重命令启动后仓储可写 |
| VI 不使用 SecurityManager | ✅ 不涉及（N/A） | 本节无沙箱代码 |
| VII 同步执行模型 | ✅ PASS（延续） | chat 循环同步阻塞；无异步模型引入 |
| VIII 三种触发源共用一个引擎 | ✅ PASS（硬约束） | CLI 是第一个接线入口：chat → `AgentService.process`，ReActLoop 不感知入口；session 三元组由入口提供、id 拼接只此一处 |
| IX Tool 模块三合一 | ✅ PASS | 不新建 Tool 模块；不触碰 axion-tool |
| 技术约束：建表/虚拟线程/日志 | ✅ PASS | schema.sql 手工增量；同步+虚拟线程；结构化日志不带用户可控值 |
| 质量门：每模块端到端测试 | ✅ PASS | 2 测试类覆盖会话层（课件 harness 基准）+ 坑九架构断言 |

无违反项，Complexity Tracking 不适用。

## Project Structure

### Documentation (this feature)

```text
specs/003-cli/
├── plan.md              # 本文件（/speckit-plan 输出）
├── research.md          # Phase 0 输出
├── data-model.md        # Phase 1 输出
├── quickstart.md        # Phase 1 输出
├── contracts/           # Phase 1 输出（cli-commands.md / session-persistence.md）
├── checklists/          # 质量清单
├── flow-status.md       # axion-spec 进度文件
└── tasks.md             # Phase 2 输出（/speckit-tasks，本阶段不创建）
```

### Source Code (repository root)

```text
axion-cli/
├── src/main/java/com/axion/cli/          # AxionCli（挂 12 子命令）、12 个 @Command 类、
│                                           #   CliAgentConfiguration（重命令装配）、
│                                           #   InitCommand 目录树逻辑
└── src/test/java/                          # 坑九架构断言测试
axion-channel-cli/
└── src/main/java/com/axion/channel/cli/   # CliChannel（读—转交—打印交互循环）
axion-storage/
├── src/main/java/com/axion/storage/       # SessionEntity 实体、SessionRepository
├── src/main/resources/schema.sql           # 增量追加 sessions DDL
└── src/test/java/                          # SessionRepositoryTest
axion-core/
├── src/main/java/com/axion/core/          # SessionManager（JPA 实现改造，签名不变）
└── src/test/java/                          # SessionManagerTest（改造为 mock 版）
```

**Structure Decision**: 沿用既有 9 模块结构，只触碰上述 4 个模块。依赖方向铁律不变：core ← 能力层；cli/channel-cli 依赖 core 与 storage（cli 组装所有模块——装配配置类落 axion-cli）。轻命令直连 SQLite 用 sqlite-jdbc（storage 传递依赖），不引入 Spring。`Session` 数据类（core）不动，持久化行由 `SessionEntity`（storage）承担，转换收口在 `SessionManager`。
