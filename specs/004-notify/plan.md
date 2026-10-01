# Implementation Plan: Notify 出站推送模块

**Branch**: `004-notify` | **Date**: 2026-09-05 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/004-notify/spec.md`（需求文档 docs/requirements/004-notify.md，课件第 19 节）

## Summary

补上出站推送这一环：`notify_channels` SQLite 全局注册表（第 4 张表）+ `NotifyChannelAdapter` 接口（接口先行，签名零渠道实现词）+ 核心阶段唯一实现 `WebhookNotifyAdapter`（通用 webhook POST）+ 纯数据解析服务 `NotifyChannelRegistry` + 内置 Tool `NotifyTools`（AxionTool 抽象）。执行链路四步钉死：注册表解析 → 装配处显式 Map 按 channelType 选 adapter → `Sandbox.enforce(HTTP_REQUEST, url)` **先于** `send` → `send`。审计复用 `ToolExecutor` 既有路径（成功 result 带渠道名）；Sandbox 注入接口、本节测试 mock；工具集注册归第 20 节。技术路线全部由需求文档 6 条拍板结论锁定，无 open 问题。

## Technical Context

**Language/Version**: Java 21（项目硬约束）

**Primary Dependencies**: Spring Boot 3.5（`spring-web` 的 `RestClient`，`RestClient.Builder` 由 Boot 自动配置）；测试新增 `com.squareup.okhttp3:mockwebserver`（全仓首次引入，Boot BOM 管理版本，属单测层不算外网依赖）；不涉及 Spring AI 模型调用（`spring-ai-model` 依赖已在模块内，本节不新增使用）

**Storage**: SQLite + Spring Data JPA；新增 `notify_channels` 表（`name` TEXT PK、`type` TEXT NOT NULL、`url` TEXT NOT NULL、`description` TEXT 可空），走 **schema.sql 手工增量**（坑八口径：MUST NOT 依赖 `ddl-auto=update` 自动迁移）；JPA 实体 + Repository 为 storage 模式机械延伸（`SessionEntity`/`SessionRepository` 先例）

**Testing**: JUnit 5 + Mockito + MockWebServer（假 webhook）；全仓 `mvn clean verify` 全绿 + 既有静态门禁（P3C/SpotBugs/FindSecBugs/PMD 等）全绿

**Target Platform**: JVM 21 服务器（Linux/Windows）

**Project Type**: Maven 多模块增量——axion-tool（`com.axion.tool.notify` 子包 + `com.axion.tool.builtin`）+ axion-storage（实体/仓储/schema.sql）；**无新模块**

**Performance Goals**: 单次推送秒级完成（同步阻塞、无异步）；`RestClient` 必设 connect/read timeout（慢 webhook 不得拖死 ReAct 轮次；具体值实现级定稿，建议 connect 3s / read 10s）

**Constraints**: 全程同步阻塞（宪法 VII）；frontmatter 不加 `notify_channels` 字段、webhook 地址不进对话不进配置键；无新增配置键；无新增 REST 端点（渠道 CRUD 归 Web Service 节）；交付清单为对外概念白名单

**Scale/Scope**: 单实例、渠道注册表条级规模；`ToolExecutor` 注入空 Map 口径不变（003 FR-10）

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 原则 | 结论 | 依据 |
|------|------|------|
| I 自实现 ReAct Loop | ✅ 合规 | 不碰模型调用与循环；notify 是 Tool，被 ReActLoop 按名调度 |
| II Spring AI 只用两件事 | ✅ 合规 | 不新增 Spring AI 使用；NotifyTools 走 `AxionTool` 抽象，不用 `@Tool` 自动执行 |
| III Provider 显式映射 | ✅ 合规 | 非 Provider 场景；adapter 按 channelType 显式 Map 选择（拍板：同一哲学，不靠容器扫描） |
| IV 一个目录 = 一个 Agent | ✅ 合规 | 不新增 Profile 字段/frontmatter 键（`notify_channels` 字段方案已拍板否决）；Agent 目录不动 |
| V 审计表 Day One 写入 | ✅ 合规 | notify 成败都落 `tool_invocations`，复用 ToolExecutor 既有成功/失败路径，不新增审计逻辑（NFR-3/FR-6） |
| VI 不用 SecurityManager / Sandbox 接口先行 | ✅ 合规 | 注入 `Sandbox` 接口；`enforce(HTTP_REQUEST, url)` 先于 `send`（坑十）；不新增 Sandbox 概念；WhitelistSandbox 实现归 23/24 节，本节 mock |
| VII 同步执行模型 | ✅ 合规 | `RestClient` 同步阻塞调用，无 Reactor/CompletableFuture/自建线程池 |
| VIII 三种触发源共用一个引擎 | ✅ 合规 | Tool 不感知触发源，CLI/Web/Scheduler 均经同一 ToolExecutor 到达 |
| IX Tool 模块三合一 | ✅ 合规 | 接口/实现/Registry 落 axion-tool（notify 子包 + builtin），不拆新模块；Agent 目录不进 Tool |
| 技术约束 | ✅ 合规 | SQLite schema.sql 手工增量（坑八）；无密钥类配置；URL 不进日志参数（NFR-2）；核心阶段不做清单遵守（无 CRUD REST、无专用渠道 Adapter、无 errcode 解析、无重试细分） |

**GATE: PASS**（Phase 1 设计后复查同表，无变化）

## Project Structure

### Documentation (this feature)

```text
specs/004-notify/
├── plan.md              # 本文件（/speckit-plan 输出）
├── research.md          # Phase 0 输出：拍板结论与技术选型裁决
├── data-model.md        # Phase 1 输出：notify_channels 表口径
├── quickstart.md        # Phase 1 输出：验证运行指南
├── contracts/           # Phase 1 输出：NotifyChannelAdapter 跨节契约
│   └── notify-channel.md
└── tasks.md             # Phase 2 输出（/speckit-tasks，非本命令产物）
```

### Source Code (repository root)

```text
axion-tool/                                    # 现有模块，增量
├── pom.xml                                     # + axion-storage、spring-web（compile）；spring-boot-starter-test、mockwebserver（test）
└── src/
    ├── main/java/com/axion/tool/
    │   ├── notify/                             # 新增子包（需求文档实现级明确）
    │   │   ├── NotifyChannelAdapter.java       # FR-1：接口先行，签名零渠道词
    │   │   ├── NotifyTarget.java               # FR-2：record（channelType + config）
    │   │   ├── WebhookNotifyAdapter.java       # FR-3：唯一实现，RestClient 构造注入
    │   │   └── NotifyChannelRegistry.java      # FR-5：纯数据解析（按名查注册表）
    │   └── builtin/                            # 新增子包
    │       └── NotifyTools.java                # FR-6：notify Tool（implements AxionTool）
    └── test/java/com/axion/tool/
        ├── notify/
        │   ├── WebhookNotifyAdapterTest.java   # 第一批：MockWebServer 假 webhook
        │   └── NotifyChannelRegistryTest.java  # 解析三态 + 缺省口径
        └── builtin/
            └── NotifyToolsTest.java            # 第二批：mock Sandbox/adapter Map/Registry，InOrder 坑十回归

axion-storage/                                 # 现有模块，增量
├── src/
│   ├── main/java/com/axion/storage/
│   │   ├── NotifyChannelEntity.java            # FR-4：JPA 实体
│   │   └── NotifyChannelRepository.java        # FR-4：Repository（storage 模式机械延伸）
│   ├── main/resources/schema.sql               # 增量追加 notify_channels（坑八口径）
│   └── test/java/com/axion/storage/
│       └── NotifyChannelRepositoryTest.java    # 手工建表脚本建表 + 存读 + 唯一约束 + 可空
```

**Structure Decision**: 无新模块——CLAUDE.md 9 模块结构不变，能力层依赖 storage 符合既定依赖方向（"axion-storage 被 core 和能力层依赖"）。装配类（RestClient bean + adapter 显式 Map）不在本节交付清单，与第 20 节工具注册同期由装配处构建（需求文档 FR-7 口径）。

## Complexity Tracking

> 仅当 Constitution Check 有违规需豁免时填写——本节无违规，留空。

无。
