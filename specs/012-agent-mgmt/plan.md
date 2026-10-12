# Implementation Plan: Agent 动态管理（012-agent-mgmt）

**Branch**: `012-agent-mgmt` | **Date**: 2026-10-02 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/012-agent-mgmt/spec.md`（需求文档 `docs/requirements/012-agent-mgmt.md`，口径 = 课件第 30 节「实现回写」最终实现）

## Summary

让 Agent 目录**实时生效**：新增 `WorkspaceWatcher`（JDK WatchService 守护线程，启动全量扫 + 实时监听 `.axion/agents/` 变更）作为统一注册入口，三条录入路径（API 上传 / 手工丢目录 / 启动扫描）汇入同一段 `AgentLifecycleService.register(agentDir)`；`AgentApiController` 扩十端点 + `WorkspaceApiController` 三端点（5.3 全表）+ 文件浏览/编辑（防目录穿越）；`AgentScheduler.unregisterProfile`（011 留白兑现）；`ToolExecutionContext` + per-agent 记忆（`agents/<name>/MEMORY.md`，Markdown 档 per-agent、无上下文回退全局、SPI 零改动）+ 全局记忆端点退役；每 Agent 一条固定会话（admin:console:<name>）；管理台新增 Agent 页（列表+新建+详情 5 tab）、移除长期记忆页。

## Technical Context

**Language/Version**: Java 21（virtual thread）

**Primary Dependencies**: Spring Boot 3.x / Spring MVC；Spring AI Alibaba（仅协议转换，宪法 II）；Picocli；SQLite + Spring Data JPA；Vue 3 + Vite（管理台前端，与 website 同栈）

**Storage**: SQLite（**无新表**——复用 sessions/llm_calls/tool_invocations；Agent 目录与归档落文件系统 `.axion/agents/`、`.axion/archive/`）

**Testing**: JUnit 5 + Mockito + AssertJ + MockMvc（standalone）；全单测不碰网络（008/010 先例）

**Target Platform**: Windows/Linux 服务器（JDK WatchService；macOS 子目录不监听差异由坑十三机制兜底）

**Project Type**: web-service + CLI（同进程守护线程，无新模块——9 模块结构不变）

**Performance Goals**: 创建/丢目录到登记可见 ≤ 数秒级；删除/注销对称；创建失败回滚零残留

**Constraints**: 全程同步阻塞（宪法 VII，守护线程不进请求链路）；core 不得反向依赖 provider/tool（依赖方向铁律）；生成调用经 LlmGateway 端口；防目录穿越 normalize+startsWith(root)（唯一安全要点）

**Scale/Scope**: 单实例、N 个 Agent 并存（N 至几十量级）；端点全集（5.3 全表）+ 2 管理台页面（1 列表 + 5 tab 详情）

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 原则 | 本节遵守方式 | 结论 |
|------|-------------|------|
| I 自实现 ReAct | 不改 ReActLoop 处理逻辑；仅 ToolExecutor.execute 增 agentName 传参（课件点名） | ✅ |
| II Spring AI 只用两件事 | generate-files 经 LlmGateway（协议转换）；无任何自动 tool 执行路径 | ✅ |
| III Provider 显式映射 | 生成用 provider 缺省取 `axion.providers` 显式映射第一个（author 配置） | ✅ |
| IV 一个目录 = 一个 Agent | 三条录入都落 `.axion/agents/<name>/`；per-agent 记忆与 AGENT.md 同目录（合原则四） | ✅ |
| V 审计 day one | generate-files 是 LLM 调用，必须落 `llm_calls`（成败都落）；会话消息经 ReAct 照常落双表 | ✅ |
| VI 无 SecurityManager | 不涉及 | ✅ |
| VII 同步执行模型 | WorkspaceWatcher 是后台守护线程（与 AgentScheduler 调度线程同类），不进请求链路；无 Reactor/CompletableFuture | ✅ |
| VIII 三触发源同一引擎 | 固定会话发消息走 `AgentService.process`（与 invoke 同入口） | ✅ |
| IX Tool 模块三合一 | 无新模块；`ToolExecutionContext` 落 axion-core（上下文机制非 Tool） | ✅ |

**模块约束**：9 模块结构不变（技术方案第 10 章）；`axion-storage` 被 core 和能力层依赖；provider/memory/tool 依赖 core；web/channel-cli 依赖所有能力层；cli 组装所有模块；boot 聚合。本节新增类落位：core（lifecycle/watcher/agentStore/ToolExecutionContext/unregisterProfile）、web（控制器/DTO/ErrorCode）、memory（内部改造）、cli（装配接线）、boot（配置键）。

## Project Structure

### Documentation (this feature)

```text
specs/012-agent-mgmt/
├── plan.md              # 本文件
├── research.md          # Phase 0 输出（R1~R10 决策记录）
├── data-model.md        # Phase 1 输出（DTO/错误码/目录布局/生命周期）
├── quickstart.md        # Phase 1 输出（机器判卷 + 人工验收）
├── contracts/           # Phase 1 输出（agent-mgmt.md 端点契约 + 跨节契约）
└── tasks.md             # Phase 2 输出（speckit-tasks，本命令不创建）
```

### Source Code (repository root)

```text
axion-core/src/main/java/com/axion/core/
├── AgentLifecycleService.java      # 新增：编排者（register/create/update/delete/get/generateFiles/saveFiles）
├── WorkspaceWatcher.java           # 新增：WatchService 守护线程（启动全量扫 + 实时监听）
├── AgentStore.java                 # 新增：write/writeAll/archive/delete 目录读写 + 脚手架模板
├── ToolExecutionContext.java       # 新增：ThreadLocal<String> agentName（per-agent 记忆上下文）
├── AgentScheduler.java             # 改造：+unregisterProfile（011 留白兑现）
├── ToolExecutor.java               # 改造：execute 增 agentName 参数（002 契约扩展）
└── MemoryService.java              # 改造：+readAll(agentName)（006 门面扩展，SPI 零改动）

axion-memory/src/main/java/com/axion/memory/
├── MemoryServiceImpl.java          # 改造：委托 store 前后临时置入 Agent 名
└── MarkdownMemoryStore.java        # 改造：构造改 workspaceRoot，运行时按上下文解析路径

axion-web/src/main/java/com/axion/web/api/
├── AgentApiController.java         # 改造：+create/get/list/update/delete/generate-files/files/session/messages/memory
├── WorkspaceApiController.java     # 新增：tree/file 读 + file 写（防穿越）
├── MemoryApiController.java        # 改造：全局端点退役 → per-agent 读取
├── ErrorCode.java                  # 改造：+AGENT_EXISTS/AGENT_NOT_FOUND/AGENT_INVALID/GENERATE_INVALID/AUTHOR_MODEL_MISSING
└── dto/AgentView.java、FileNode.java、CreateAgentRequest.java、UpdateAgentRequest.java、
    GenerateFilesResponse.java、SessionView.java   # 新增 DTO

axion-cli/src/main/java/com/axion/cli/
└── CliAgentConfiguration.java      # 改造：watcher 装配（守护线程）、校验钩子注入、author 配置注入

axion-boot/src/main/resources/application.yaml   # 改造：+axion.author.provider/model 配置键

axion-web/src/main/frontend/src/
├── views/AgentView.vue             # 新增：Agent 列表 + 新建（name+description）
├── views/AgentDetailView.vue       # 新增：详情 5 tab（基本信息/生成/文件/会话/记忆）
├── views/MemoryView.vue            # 移除：全局记忆页退役
├── api.js                          # 改造：+apiPost/apiPut/apiDelete（双信封单一解析点）
├── main.js / App.vue               # 改造：路由 + 导航（Agent 管理入口）
└── style.css                       # 沿用深蓝 token（2026-10-01 拍板）
```

**Structure Decision**: 零新模块；编排与基础设施类全部落 axion-core（依赖方向铁律，生成经 LlmGateway 端口）；端点与 DTO 落 axion-web；装配在 axion-cli 的 CliAgentConfiguration（与 AgentScheduler/WhitelistSandbox 同款模式）。

## Complexity Tracking

无违规项（宪法 9 条全过、9 模块不变、无新增第三方依赖）。
