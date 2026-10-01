# Implementation Plan: Memory 三层记忆

**Branch**: `006-memory` | **Date**: 2026-09-06 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/006-memory/spec.md`（需求文档 docs/requirements/006-memory.md，课件第 21/22 节新版 PDF）

## Summary

把"记得住事"接进 Agent：`MemoryService` 统一门面（接口落 core 依赖倒置，LlmGateway 先例）内部收口会话记忆（SessionManager/SQLite，002 已交付）与长期记忆（`LongTermMemoryStore` 三档后端：Markdown 默认 / Sqlite / Mem0，`memory.backend` 一行换档——拍板 B）；四条行为契约全实现共用（坑十五~十八）；MarkdownMemoryStore 带并发与原子写约定（双层互斥 + 锁内重读 + ATOMIC_MOVE）；`SaveMemoryTool`/`RecallMemoryTool`（AxionTool 纯实现）注册进 ToolRegistry；PromptBuilder 集成 buildContext 注入 system prompt（002 改造点）。技术路线全部由拍板 B + 设计期自审修复 #1~#9 锁定，无 open 问题。

## Technical Context

**Language/Version**: Java 21（项目硬约束）

**Primary Dependencies**: 零新依赖——JDK 原生（synchronized/FileChannel/ATOMIC_MOVE/虚拟线程）、RestClient（005 已引入，Mem0 档复用）、SQLite + Spring Data JPA（既有）；Mem0 无官方 SDK（RestClient 直连，H3 实施时核实协议）

**Storage**: `.axion/memory/MEMORY.md`（Markdown 档，003 init 已建模板）+ `memory_entries` 新表（Sqlite 档，手工 schema.sql 增量，坑八口径）；会话记忆复用 sessions 表

**Testing**: JUnit 5 + Mockito + MockWebServer（005 已有）+ @TempDir；`mvn clean verify` 全绿 + 既有静态门禁

**Target Platform**: JVM 21 服务器（Linux/K8s 生产；Windows 本机测试，005 平台口径）

**Project Type**: Maven 多模块增量——axion-core（3 个接口/枚举 + PromptBuilder 改造，依赖倒置端口）、axion-memory（实现/三档后端/两 Tool，从空壳起步）、axion-storage（MemoryEntry 实体 + Repository + schema 增量）、axion-cli（CliAgentConfiguration 装配）；**无新模块**

**Performance Goals**: 同步阻塞（虚拟线程承载）；markdown 档每轮读 KB 级文件毫秒级；sqlite 档每轮两次查询；**mem0 档每轮一次 REST 调用（~100ms-1s，诚实标注；缓存与坑十五张力记录，信号驱动）**

**Constraints**: 全程同步阻塞（宪法 VII）；交付清单为对外概念白名单；单实例假设（跨进程 FileChannel 锁为纵深防御）；后端故障快速失败不静默空记忆不自动降级；G4-C1 组件注解纪律延续

**Scale/Scope**: 单实例多 Agent（跨会话虚拟线程并发是真实形态）；记忆量级 markdown 档 <4000 字归档、sqlite 档千条级、mem0 档外置

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 原则 | 结论 | 依据 |
|------|------|------|
| I 自实现 ReAct Loop | ✅ 合规 | 不碰循环；记忆经 PromptBuilder 注入、经 Tool 读写 |
| II Spring AI 只用两件事 | ✅ 合规 | 无模型调用新增；两 Tool 走 AxionTool 抽象（005 机械适配） |
| III Provider 显式映射 | ✅ 合规 | 非 Provider 场景；memory.backend 按配置显式 @Bean 装配（不扫描） |
| IV 一个目录 = 一个 Agent | ✅ 合规 | 不新增 Profile 字段；MEMORY.md 是数据文件非配置 |
| V 审计表 Day One 写入 | ✅ 合规 | save/recall 复用 ToolExecutor 审计路径（005 注册），零新增审计逻辑 |
| VI 不用 SecurityManager / Sandbox 接口先行 | ✅ 合规 | 两 Tool 无涉外 IO（文件/DB 属底座内存域，非沙箱管辖的 FILE_READ 语义——记忆文件是底座自己的数据文件，不走 Agent 文件工具路径；如需沙箱化归扩展阶段，本课如实说明） |
| VII 同步执行模型 | ✅ 合规 | 全同步（FileChannel/synchronized/RestClient 同步） |
| VIII 三种触发源共用一个引擎 | ✅ 合规 | Memory 不感知触发源 |
| IX Tool 模块三合一 | ✅ 合规 | 两 Tool 落 axion-memory（CLAUDE.md 模块口径：MemoryTools 归 memory）；注册进 ToolRegistry 由装配处完成，不拆新模块 |
| 技术约束 | ✅ 合规 | schema.sql 手工增量（坑八）；Mem0 凭证 `${ENV_VAR}` 占位；记忆内容不进日志参数；核心阶段不做清单遵守（无自动提炼/向量/情景记忆） |

**GATE: PASS**（Phase 1 设计后复查同表，无变化）

## Project Structure

### Documentation (this feature)

```text
specs/006-memory/
├── plan.md              # 本文件（/speckit-plan 输出）
├── research.md          # Phase 0 输出：拍板与选型裁决
├── data-model.md        # Phase 1 输出：MEMORY.md 两区块 + memory_entries 口径
├── quickstart.md        # Phase 1 输出：验证运行指南
├── contracts/           # Phase 1 输出：MemoryService 跨节契约
│   └── memory-service.md
└── tasks.md             # Phase 2 输出（/speckit-tasks，非本命令产物）
```

### Source Code (repository root)

```text
axion-core/                                    # 依赖倒置端口（001 LlmGateway 先例）+ 改造点
└── src/main/java/com/axion/core/
    ├── MemoryService.java                      # FR-1：门面接口（buildContext/remember/recall）
    ├── MemoryScope.java                        # FR-2：CORE/ARCHIVAL
    ├── LongTermMemoryStore.java                # FR-2：可插拔后端接口（四条行为契约）
    └── PromptBuilder.java                      # FR-8：构造器 +MemoryService 参数（002 交付物改造）

axion-memory/                                  # 从空壳起步（当前仅 package-info）
└── src/
    ├── main/java/com/axion/memory/
    │   ├── MemoryServiceImpl.java              # FR-1：实现（委托 SessionManager + LongTermMemoryStore）
    │   ├── MarkdownMemoryStore.java            # FR-3：默认档（并发/原子写约定）
    │   ├── SqliteMemoryStore.java              # FR-4：memory_entries 档
    │   ├── Mem0MemoryStore.java                # FR-5：自托管 REST 档
    │   ├── SaveMemoryTool.java                 # FR-7：save_memory（AxionTool）
    │   └── RecallMemoryTool.java               # FR-7：recall_memory（AxionTool）
    └── test/java/com/axion/memory/
        ├── MarkdownMemoryStoreTest.java        # 坑十五~十八 + 并发回归
        ├── LongTermMemoryStoreTest.java        # 参数化契约（三档四条）
        ├── SqliteMemoryStoreTest.java
        ├── Mem0MemoryStoreTest.java            # mock HTTP 层
        ├── MemoryToolsTest.java
        └── MemoryServiceTest.java

axion-storage/                                 # 模式机械延伸
├── src/main/java/com/axion/storage/
│   ├── MemoryEntry.java                        # FR-4：实体
│   └── MemoryEntryRepository.java              # FR-4：Repository
└── src/main/resources/schema.sql               # 增量追加 memory_entries（坑八）

axion-cli/
└── src/main/java/com/axion/cli/CliAgentConfiguration.java
    # FR-6/FR-7/FR-8：memory.backend 装配 + 两 Tool 注册 + MemoryService 注入 PromptBuilder
```

**Structure Decision**: 无新模块——9 模块结构不变。MemoryService/LongTermMemoryStore/MemoryScope 落 core 是**依赖倒置端口**（PromptBuilder 在 core 调它们，core←memory 反向依赖不成立；001 LlmGateway 同款先例），非业务逻辑进 core。MemoryTools 落 axion-memory（CLAUDE.md 模块结构明文），注册进 ToolRegistry 由装配处完成。改造点：PromptBuilder（002 交付物，技术方案 §5.3 明文集成点）+ CliAgentConfiguration（003/005 交付物）。

## Complexity Tracking

> 仅当 Constitution Check 有违规需豁免时填写——本节无违规，留空。

无。
