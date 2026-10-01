# Implementation Plan: Agent Provider 模块（对接 LLM）

**Branch**: `001-provider` | **Date**: 2026-08-30 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/001-provider/spec.md`

## Summary

在工程地基之上，为 `axion-provider` / `axion-core` / `axion-storage` 三个空壳模块交付第一批业务代码：`ProviderService` 统一调用门面（provider name → ChatModel 显式映射、Function Calling 只翻译不执行、成败都落 `llm_calls` 审计）+ `Profile` 派生与注册 + `LlmCall` 落库。宪法原则 II（Spring AI 只用两件事）、III（显式映射）、V（审计 day one）、VII（同步执行）是四条硬约束，均有对应回归测试钉死。

## Technical Context

**Language/Version**: Java 21 + Spring Boot 3.x，Maven 多模块（BOM 已锁定：spring-ai-bom 1.1.8、snakeyaml 2.4、sqlite-jdbc 3.53.4.0）

**Primary Dependencies**: Spring AI Alibaba（只做协议转换 + `@Tool` schema 生成，禁用自动 tool 执行）、Spring Data JPA + SQLite、SnakeYAML（axion-core 已声明）、JUnit 5 + Mockito（工程地基测试栈，以模块 pom 实际锁定为准）

**Storage**: SQLite `.axion/axion.db`；`llm_calls` 用手工 `schema.sql` 建表，不依赖 `hibernate.ddl-auto=update` 迁移（技术方案 §9.2 工程风险提示）

**Testing**: 四个单测类 mock `ChatModel` 默认全跑；`ProviderSmokeIT` 打 `@Tag("integration")` 本地手动、CI 跳过；完成定义 = `mvn clean verify` 全绿

**Target Platform**: 企业服务器 / 本地单节点（Windows/Linux），JDK 21

**Project Type**: Maven 多模块 Agent 底座运行时（本 feature 为 3 个模块的第一批业务代码）

**Performance Goals**: 节级无硬指标；同步阻塞 + 虚拟线程承载并发（系统级性能验收归需求文档 §13，后续节验收）

**Constraints**: 宪法 9 条（重点 II/III/V/VII）；模块结构 MUST 与技术方案第 10 章 9 模块一致；敏感配置环境变量占位；全程不自动 commit/push/package.sh

**Scale/Scope**: 单实例多 Agent 并存；核心阶段 ≥2 家 Provider（验收 DeepSeek + Kimi，缺 key 至少一家真实跑通）

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 原则 | 本节判定 | 依据 |
|------|---------|------|
| I 自实现 ReAct Loop | ✅ 不涉及（N/A） | 本节不实现循环；ProviderService 只做单次调用，不含任何循环/调度逻辑（NFR-3 边界） |
| II Spring AI 只用两件事 | ✅ PASS（硬约束） | 只做协议转换 + schema 生成；自动执行关闭有回归测试钉死（FR-4/FR-9 + ToolSchemaAdapterTest/ProviderServiceTest） |
| III Provider 显式映射 | ✅ PASS（硬约束） | Map<String, ChatModel> 显式建表，禁止类型扫描；架构断言 + 路由不串台测试（FR-3） |
| IV 一个目录 = 一个 Agent | ✅ 不涉及（N/A） | 本节无 Agent 目录/Skill 绑定逻辑 |
| V 审计表 Day One 写入 | ✅ PASS（硬约束） | llm_calls 成败都落，含 success/error_message 列（FR-5/FR-6） |
| VI 不使用 SecurityManager | ✅ 不涉及（N/A） | 本节无沙箱 |
| VII 同步执行模型 | ✅ PASS（硬约束） | 全程同步阻塞，无 Reactor/CompletableFuture（NFR-1） |
| VIII 三种触发源共用一个引擎 | ✅ 不涉及（N/A） | Provider 不感知入口 |
| IX Tool 模块三合一 | ✅ PASS | 不新建 Tool 模块；AxionTool 接口落 axion-core（US-4 在 axion-tool 填充实现） |
| 技术约束：建表/密钥/日志 | ✅ PASS | 手工 schema.sql；`${ENV_VAR}` 占位；结构化 JSON 日志（地基已有） |
| 质量门：每模块端到端测试 | ✅ PASS | 5 个测试类覆盖三个模块（计划见需求文档验收 harness） |

无违反项，Complexity Tracking 不适用。

## Project Structure

### Documentation (this feature)

```text
specs/001-provider/
├── plan.md              # 本文件（/speckit-plan 输出）
├── research.md          # Phase 0 输出
├── data-model.md        # Phase 1 输出
├── quickstart.md        # Phase 1 输出
├── contracts/           # Phase 1 输出（provider-service.md）
├── checklists/          # 质量清单
├── flow-status.md       # axion-spec 进度文件
└── tasks.md             # Phase 2 输出（/speckit-tasks，本阶段不创建）
```

### Source Code (repository root)

```text
axion-provider/
└── src/main/java/com/axion/provider/   # ProviderProperties、ProviderService、显式映射、
│                                        #   Function Calling 适配器、ProviderNotFoundException
├── src/test/java/                       # ProviderServiceTest、ToolSchemaAdapterTest、ProviderSmokeIT
axion-core/
├── src/main/java/com/axion/core/       # Profile、ProfileLoader（基础版 deriveProfile）、
│                                        #   ProfileRegistry、Message、AxionTool
└── src/test/java/                       # ProfileLoaderTest
axion-storage/
├── src/main/java/com/axion/storage/    # LlmCall 实体、LlmCallRepository
├── src/main/resources/schema.sql        # 手工建表脚本
└── src/test/java/                       # LlmCallRepositoryTest（执行 schema.sql 建表）
axion-boot/
└── src/main/resources/application.yaml  # axion.providers 全局层配置示例（${ENV} 占位）
```

**Structure Decision**: 沿用既有 9 模块 Maven 结构，本 feature 只触碰上述三个模块与 boot 配置示例。`axion-core` 当前为空壳，本节为其落地第一批抽象（Profile/Message/AxionTool），不修改任何既有接口（不存在）；新增能力一律加新模块的原则对本节不适用（本节是 core 的首批内容本身）。
