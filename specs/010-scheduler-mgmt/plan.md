# Implementation Plan: 010-scheduler-mgmt 定时任务子系统（状态持久化 + 管理端点 + 重启恢复 + 多 Agent 并存）

**Branch**: `010-scheduler-mgmt` | **Date**: 2026-09-09 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/010-scheduler-mgmt/spec.md`（S1+S2 已完成，3 题澄清已写回）

## Summary

把 008 的内存注册 cron 升级为完整定时任务子系统：两张表（`scheduled_tasks` / `task_executions`）让「跑过几次、上次成功没、下次几点」重启不丢；`Profile.Schedule` 补 `id`（拍板 A）作为 task_id 与锁 key（008 派生 key 退役）；`AgentScheduler` 改造（登记 / 启用检查 / 成败都记 / runNow，⑦a 同锁同入口）；`ScheduledTaskStore` 契约在 core、JPA 实现在 storage（依赖倒置）；`ScheduleApiController` 四端点（双信封 + 404 + ⑦b 60s 同步 + 504）；管理台「定时任务」页（第一个写操作页，⑦d skill 只读纪律加例外）；Demo 前置环境配齐。**零新第三方依赖**，纯增量 + 5 处经拍板的改造点。

技术决策全文见 [research.md](./research.md)（R1~R13），数据模型见 [data-model.md](./data-model.md)，API 契约见 [contracts/schedules-api.md](./contracts/schedules-api.md)，验证入口见 [quickstart.md](./quickstart.md)。

## Technical Context

**Language/Version**: Java 21（virtual thread）；Spring Boot 3.x（已实测环境）；Maven 多模块

**Primary Dependencies**: 零新增——Spring Scheduling（ThreadPoolTaskScheduler + CronTrigger，008 已用）、Spring Data JPA + SQLite（既有栈）、Spring MVC、Vue 3 + Vite（既有前端）

**Storage**: SQLite（`jdbc:sqlite:.axion/axion.db`，`ddl-auto: none` + `spring.sql.init.mode: always` 手工建表脚本管线，既有）；新增两表进 schema.sql 增量

**Testing**: JUnit 5 + Mockito + AssertJ（既有）；standalone MockMvc（009 模式）；@SpringBootTest + 临时 SQLite + mock provider（E2E 进 gate）；@Tag("integration") 真 key IT（既有先例）

**Target Platform**: 企业服务器 / 本机（Windows dev）；HTTP 8080

**Project Type**: Java 多模块 Spring Boot Web 服务（9 模块，本节动 core / storage / web / cli / boot 五模块 + 前端）

**Performance Goals**: 无新性能目标；run 端点同步等待 60s 上限（virtual thread 承受阻塞，宪法 VII）

**Constraints**: 全程同步阻塞（宪法 VII）；停用即不跑不记历史（NFR-2）；单任务失败不拖调度器（008 延续）；时间存 UTC（Clarifications）；状态与历史非原子最终一致（接受）；不新增第三方依赖；不改 9 模块结构

**Scale/Scope**: 单实例；定时任务数 = frontmatter schedules 条目数（个位~几十）；task_executions 长期增长清理归扩展（每分钟任务一年 52 万行，接受）

## Constitution Check

*GATE: 进入 Phase 0 前已过；Phase 1 设计后复核。*

| # | 原则 | 本节如何满足 | 复核 |
|---|------|-------------|------|
| I | 自实现 ReAct Loop | 定时链路复用 AgentService.process → 既有 ReActLoop，零新循环代码 | ✅ |
| II | Spring AI 只用两件事 | 本节不碰 LLM 调用层；无 ChatClient 自动 tool 执行路径新增 | ✅ |
| III | Provider 显式映射 | 不新增 Provider；E2E mock 替换 ProviderService 不引入扫描 | ✅ |
| IV | 一个目录 = 一个 Agent | 不新增 Agent 概念；schedules 定义仍在 AGENT.md frontmatter（表只存状态+历史，非定义源） | ✅ |
| V | 审计 Day One | `task_executions` 成功失败都记（与 llm_calls/tool_invocations 同源）；两张表核心阶段即写入 | ✅ |
| VI | 无 SecurityManager | 不涉及沙箱实现；Sandbox 接口零改动 | ✅ |
| VII | 同步执行模型 | runWithTimeout = FutureTask + virtual thread 的同步等待（009 已拍板形态）；无 Reactor/CompletableFuture 业务路径 | ✅ |
| VIII | 三种触发源共用一个引擎 | 钟推走 AgentService.process（008 已实现）；channel/user 固定 scheduler；runNow 同样走 executeInternal → process | ✅ |
| IX | Tool 模块三合一 | 不新增 Tool；AgentLoader 改造在 core（Agent 目录不是 Tool） | ✅ |

## Project Structure

### Documentation (this feature)

```text
specs/010-scheduler-mgmt/
├── plan.md              # 本文件
├── research.md          # Phase 0：R1~R13 技术决策（H3 核实点标注）
├── data-model.md        # Phase 1：两实体 + 状态机 + 校验规则
├── quickstart.md        # Phase 1：验证指南（机器判卷 + 人工项）
├── contracts/
│   └── schedules-api.md # Phase 1：四端点双信封契约
├── spec.md              # S1+S2 产物
├── flow-status.md       # 流程状态
└── tasks.md             # Phase 2（/speckit-tasks 产出，本命令不创建）
```

### Source Code (repository root)

```text
axion-core/src/main/java/com/axion/core/
├── Profile.java                   # 改造：Schedule record 补 id（首位字段，拍板 A）
├── ProfileLoader.java             # 改造：schedules 解析读 id + ⑦c 缺失报错（003 交付物）
└── AgentScheduler.java            # 改造：登记/启用检查/executeInternal/runNow/同锁（008 交付物）

axion-storage/src/main/java/com/axion/storage/
├── ScheduledTaskStore.java        # 新增接口（落位拍板 2026-09-09：契约与实现同落 storage，R1）
├── ScheduledTaskView.java         # 新增值对象
├── TaskExecutionView.java         # 新增值对象
├── ScheduledTask.java             # 新增实体（手写 getter，R8）
├── TaskExecution.java             # 新增实体
├── ScheduledTaskRepository.java   # 新增仓库
├── TaskExecutionRepository.java   # 新增仓库
└── JpaScheduledTaskStore.java     # 新增实现（R1）

axion-storage/src/main/resources/schema.sql   # 改造：增量两表（003 交付物）

axion-web/src/main/java/com/axion/web/api/
└── ScheduleApiController.java     # 新增：四端点 + DTO（双信封，009 模式）

axion-web/src/main/frontend/src/
├── api.js                         # 改造：增 apiPost / apiPut（双信封同款）
├── main.js                        # 改造：/schedules 路由
├── App.vue                        # 改造：navItems 增「定时任务」
└── views/SchedulesView.vue        # 新增：列表 + 立即执行/启用停用

axion-cli/src/main/java/com/axion/cli/
└── CliAgentConfiguration.java     # 改造：JpaScheduledTaskStore Bean + AgentScheduler 装配增参

axion-boot/src/main/resources/application.yaml   # 改造：http.allowed_domains 三域名（FR-7）

.claude/skills/axion-admin-ui/SKILL.md          # 改造：⑦d 只读纪律例外条款

CLAUDE.md                                        # 改造：AGENT.md schedules 示例补 id（FR-4）

# 测试（随模块）
axion-core/src/test/.../AgentSchedulerTest.java        # 增补：登记/停用跳过/runNow/⑦a 并发/⑦c
axion-core/src/test/.../ProfileLoaderTest.java         # 增补：id 解析 + 缺失报错
axion-storage/src/test/.../ScheduledTaskRepositoryTest.java  # 新增：两表 PRAGMA + 存取
axion-web/src/test/.../ScheduleApiControllerTest.java  # 新增：四端点契约
axion-boot/src/test/.../ScheduledTaskE2ETest.java      # 新增：gate 内无 key 五步（mock provider）
axion-boot/src/test/.../SchedulerFlowIT.java           # 新增：@Tag integration 真 key 对账
axion-boot/src/test/.../RestartRecoveryIT.java         # 新增：@Tag integration 重启四样恢复
```

## 关键设计点（详见 research.md / data-model.md / contracts/）

1. **依赖倒置（R1，落位拍板 2026-09-09）**：`ScheduledTaskStore` 六方法（register / recordExecution / isEnabled / setEnabled / list / executions）与实现同落 **storage**（Maven 依赖方向 storage 不得依赖 core；与 SessionRepository 同构）；`AgentScheduler`（core）与 `ScheduleApiController`（web）都只依赖接口；由 CliAgentConfiguration 显式 @Bean 装配（宪法 III 哲学）
2. **AgentScheduler 改造（R3）**：锁 key/task_id 直接用 schedule id；补 `taskId → (Profile, Schedule)` 注册信息映射供 runNow；runOnce = enabled 检查 → executeInternal；⑦a runNow 与到点同锁同 executeInternal
3. **状态机（data-model）**：停用跳过不记历史；锁占跳过不记历史；成败都记 + 状态更新；setEnabled 只动 enabled 字段（next_run_at 保留）
4. **时间口径（R2）**：Instant + InstantTextConverter（UTC）；zone 字段随 API 行，展示转换在前端
5. **504 复用（R5/R6）**：AgentTimeoutException + runWithTimeout 模式照抄 009，不新建异常类
6. **⑦d skill 更新（R7）**：只读纪律第 4/5 条 + 验收清单第 2 条加定时任务页例外
7. **§9.2 不一致（R12）**：`updated_at` 行不落地，收尾报告用户拍板是否回写技术方案

## 待核实清单（H3，实现任务开始前逐项）

- [ ] R4：Spring 6.2 CronTrigger/CronExpression 计算 next 触发时刻的公开 API 形态与 zone 参与方式（本地 jar 反查；不足则停下报告，不得新增第三方依赖）
- [ ] R10：mock provider 注入方式（@MockitoBean vs 测试 @Configuration 替换 ProviderService）在本项目 Spring Boot 版本下的可用形态
- [ ] FR-7：notify_channels 行人工 INSERT 的通道（004 无种子机制——人工项，确认 sqlite3 或临时脚本途径）
