# Implementation Plan: 008-scheduler（定时任务钟推）

**Branch**: `008-scheduler` | **Date**: 2026-09-07 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/008-scheduler/spec.md`（需求文档 docs/requirements/008-scheduler.md，修订说明 ①~⑦ 已钉死口径）

## Summary

补上第三种触发源（钟推）：`AgentScheduler`（axion-core）启动扫描 Profile.schedules 逐条动态注册 `ThreadPoolTaskScheduler` + `CronTrigger`，到点 `runOnce` 按派生锁 key tryLock（坑二防重叠）→ 固定 `("scheduler","scheduler",profileName)` 三元组取 Session → 调 `AgentService.process`（与 CLI/Web 完全同一入口，审计零新增）→ 失败 catch 记日志不崩调度器 + finally 放锁（坑三）。装配在 CliAgentConfiguration（ThreadPoolTaskScheduler `setPoolSize(4)` ⑦a + AgentScheduler bean + 显式 registerAll）。zone 非法启动报错（⑦b）、ScheduledFuture 句柄存 Map（⑦c）、28 节 task_id 重议注记（⑦d）。

## Technical Context

**Language/Version**: Java 21（宪法 VII 虚拟线程；调度线程池由 Spring 承担）

**Primary Dependencies**: `ThreadPoolTaskScheduler`/`CronTrigger`（spring-context 既有）、`ReentrantLock`/`ConcurrentHashMap`/`ZoneId`/`TimeZone`（JDK 原生）——零新第三方依赖；JUnit 5 + Mockito（既有测试栈）

**Storage**: 无新增（scheduled_tasks/task_executions 归 28 节）

**Testing**: `AgentSchedulerTest`（core，四坑 harness + 两个最值钱 + 会话三元组 + ⑦b 非法 zone）+ `CliAgentConfigurationTest` 增补（bean 存在 + poolSize>1 ⑦a 回归钉）+ 全量 `mvn clean verify`

**Target Platform**: Windows（开发本机）与 Linux（部署目标）——cron/时区语义平台无关

**Project Type**: Maven 多模块（9 模块不动；AgentScheduler 落 axion-core，装配改造落 axion-cli）

**Performance Goals**: 注册阶段 O(profiles×schedules) 一次扫描；触发开销 = 锁获取（微秒）+ 会话获取（内存）——相对 ReAct 秒级延迟可忽略

**Constraints**: 宪法 VIII（三触发源同一 AgentService 入口）、VII（任务体同步阻塞）、V（审计零新增复用既有）；交付清单白名单；前序零改动（Profile/ProfileRegistry/SessionManager/AgentService 原样）

**Scale/Scope**: 核心阶段单实例；一个类 + 一处装配改造 + 一个测试类增补；无新表、无新依赖、无新配置键

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 原则 | 本 feature 关系 | 结论 |
|------|---------------|------|
| I 自实现 ReAct Loop | 不涉及（002 已交付，钟推走同一引擎） | ✅ |
| II Spring AI 只用两件事 | 不涉及 | ✅ |
| III Provider 显式映射 | 不涉及 | ✅ |
| IV 一个目录 = 一个 Agent | schedules 定义在 AGENT.md frontmatter（002/003 已交付解析），本节零改动 | ✅ |
| V 审计表 Day One 写入 | FR-003：钟推调用走 AgentService 内部既有审计路径（llm_calls/tool_invocations），零新增审计逻辑 | ✅ |
| VI Sandbox 接口先行 | 不涉及 | ✅ |
| VII 同步执行模型 | NFR-001：任务体同步阻塞调 process；ThreadPoolTaskScheduler 是陷阱表点名组件（非自建线程池禁条）；不引入 Reactor/CompletableFuture | ✅ |
| VIII 三种触发源共用一个引擎 | **本 feature 主体**：钟推汇入 AgentService.process；Session channel/user 固定 scheduler | ✅ |
| IX Tool 模块三合一 | 不涉及 | ✅ |

无违反 → Complexity Tracking 不需要。

## Project Structure

### Documentation (this feature)

```text
specs/008-scheduler/
├── plan.md              # 本文件（/speckit-plan 产物）
├── research.md          # Phase 0 裁决记录（修订说明 ①~⑦ 的裁决与备选）
├── data-model.md        # Phase 1 数据模型（无新表；Schedule 三字段 + 派生 key + 句柄 Map）
├── quickstart.md        # Phase 1 验证指南（harness 跑法 + 真实到点触发人工项）
├── contracts/           # Phase 1 接口契约
│   └── scheduler.md
└── tasks.md             # Phase 2（/speckit-tasks，非本命令产物）
```

### Source Code (repository root)

```text
axion-core/src/main/java/com/axion/core/
├── AgentScheduler.java   # 本节新增（钟推入口，无组件注解纯类）
└── Profile.java          # 002 已交付，零改动（Schedule 三字段不动——拍板 B）

axion-cli/src/main/java/com/axion/cli/
└── CliAgentConfiguration.java   # 本节改造：+ThreadPoolTaskScheduler @Bean（setPoolSize(4)）
                                 # +AgentScheduler @Bean + registerAll 显式调用

axion-core/src/test/java/com/axion/core/
└── AgentSchedulerTest.java      # 本节新增（四坑 harness + 两最值钱 + 三元组 + ⑦b）
axion-cli/src/test/java/com/axion/cli/
└── CliAgentConfigurationTest.java  # 本节增补（bean 存在 + poolSize>1）
```

**Structure Decision**: 沿用 9 模块与既有包结构（AgentScheduler 落 axion-core——技术方案 §8.5 明文「归 axion-core」；装配改造落 axion-cli——003/007 先例）。不新建模块、不改依赖方向。
