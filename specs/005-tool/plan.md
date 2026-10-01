# Implementation Plan: Tool 体系

**Branch**: `005-tool` | **Date**: 2026-09-05 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/005-tool/spec.md`（需求文档 docs/requirements/005-tool.md，课件第 20 节）

## Summary

把 Agent 的"手"接上：`ToolRegistry` 统一注册三来源工具（内置六件 + 方式二 MCP + 方式三 @Tool 包装），按 Profile.tools 过滤"不多不少"；内置工具 execute 首行 `sandbox.enforce` 先于 IO（坑十）；MCP 失联只 WARN 不拖垮启动（坑十三）；契约三件套参数化兜底（坑十二）；装配处改造（CliAgentConfiguration）完成 004 遗留接线（RestClient 不变量 9 + NotifyTools 显式 @Bean）+ 拍板方案 A 临时 PermissiveSandbox（24 节替换）；安全窗口期间执行保守 Profile 纪律。技术路线全部由需求文档拍板 + 设计期自审修订锁定，无 open 问题。

## Technical Context

**Language/Version**: Java 21（项目硬约束）

**Primary Dependencies**: 既有——SnakeYAML（003）、spring-web/RestClient（004）、spring-boot-starter-test + mockwebserver + spotbugs-annotations（004）；**新增** `org.springframework.ai:spring-ai-starter-mcp-client`（版本随 spring-ai-bom 1.1.8；本地实测 spring-ai-mcp 仅协议壳无 client 类，实施时 H3 核实 McpSyncClient/stdio transport 实际 API）

**Storage**: 无新表——MCP 配置走 `.axion/mcp_servers.yaml`（003 init 已建模板）；审计复用 `tool_invocations`

**Testing**: JUnit 5 + Mockito + MockWebServer（004 已有）；MCP 测试 mock client；`mvn clean verify` 全绿 + 既有静态门禁

**Target Platform**: JVM 21 服务器；生产 Linux/K8s（bash 可用）；Windows 本机测试经 Git Bash 的 bash（003 同款口径）

**Project Type**: Maven 多模块增量——axion-tool（ToolRegistry/builtin 六件/mcp 子包/方式三包装器/PermissiveSandbox）+ axion-cli（CliAgentConfiguration 改造）；**无新模块**

**Performance Goals**: 工具执行同步阻塞（虚拟线程承载）；shell 超时默认 30s（超时强制销毁）；http 响应体上限 1MB；MCP stdio 每次调用进程间通信（可接受，虚拟线程挂起等待）

**Constraints**: 全程同步阻塞（宪法 VII，McpSyncClient 同步门面）；交付清单为对外概念白名单；G4-C1 组件注解纪律（纯类交付 + 装配处显式 @Bean；McpClientService 依赖就位可 @Component）；安全窗口纪律（20~23 节保守 Profile）

**Scale/Scope**: 单实例；工具注册量级 = 内置 6 + 业务方 MCP/方式三若干（条级到几十级）；ToolRegistry 用 ConcurrentHashMap

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 原则 | 结论 | 依据 |
|------|------|------|
| I 自实现 ReAct Loop | ✅ 合规 | 不碰循环；工具被 ToolExecutor 按名调度 |
| II Spring AI 只用两件事 | ✅ 合规 | 方式三仅借扫描 + schema 生成；自动执行禁用（坑二断言测试钉死）；MCP 用 MCP 协议栈不经 Spring AI Agent 抽象 |
| III Provider 显式映射 | ✅ 合规 | 非 Provider 场景；装配处显式 @Bean 组装全部工具与依赖，不靠扫描发现 |
| IV 一个目录 = 一个 Agent | ✅ 合规 | 不新增 Profile 字段；ContextLoader/Agent 目录归 core 不翻案（宪法 IX） |
| V 审计表 Day One 写入 | ✅ 合规 | 复用 ToolExecutor 既有路径，本课零新增审计逻辑 |
| VI 不用 SecurityManager / Sandbox 接口先行 | ✅ 合规 | 六个工具 execute 首行 enforce（坑十）；PermissiveSandbox 临时实现（拍板方案 A），24 节替换 WhitelistSandbox；接口签名零实现词 |
| VII 同步执行模型 | ✅ 合规 | 同步阻塞；McpSyncClient 同步门面（业务层零 Reactor 代码） |
| VIII 三种触发源共用一个引擎 | ✅ 合规 | Tool 不感知触发源 |
| IX Tool 模块三合一 | ✅ 合规 | 全部落 axion-tool（builtin/mcp 子包），不拆新模块 |
| 技术约束 | ✅ 合规 | 无新表/无新配置键（mcp_servers.yaml 是工作区文件，003 已建模板）；凭证 `${ENV_VAR}` 占位；入参不进日志参数；核心阶段不做清单遵守（无 Tool Policy/SSE/容器沙箱/并行调用） |

**GATE: PASS**（Phase 1 设计后复查同表，无变化）

## Project Structure

### Documentation (this feature)

```text
specs/005-tool/
├── plan.md              # 本文件（/speckit-plan 输出）
├── research.md          # Phase 0 输出：拍板结论与选型裁决
├── data-model.md        # Phase 1 输出：ToolRegistry/McpServerConfig 口径
├── quickstart.md        # Phase 1 输出：验证运行指南
├── contracts/           # Phase 1 输出：ToolRegistry 跨节契约
│   └── tool-registry.md
└── tasks.md             # Phase 2 输出（/speckit-tasks，非本命令产物）
```

### Source Code (repository root)

```text
axion-tool/                                    # 现有模块，增量
├── pom.xml                                     # + spring-ai-starter-mcp-client
└── src/
    ├── main/java/com/axion/tool/
    │   ├── ToolRegistry.java                   # FR-1：统一注册 + 过滤 + 重名拒绝
    │   ├── PermissiveSandbox.java              # FR-7：临时全放行（24 节替换）
    │   ├── AnnotatedMethodToolAdapter.java     # FR-6：方式三包装器
    │   ├── builtin/                            # FR-2/3/4：内置六件
    │   │   ├── ReadFileTool.java
    │   │   ├── WriteFileTool.java
    │   │   ├── ListDirTool.java
    │   │   ├── ShellTools.java
    │   │   ├── HttpTools.java（http_get/http_post 两件或两实现类，实现级明确）
    │   │   └── NotifyTools.java（004 已交付，原样使用）
    │   └── mcp/                                # FR-5：方式二
    │       ├── McpClientService.java
    │       ├── McpToolAdapter.java
    │       └── McpServerConfig.java
    └── test/java/com/axion/tool/
        ├── AxionToolContractTest.java           # 坑十二参数化
        ├── ToolRegistryTest.java               # 坑十四 + 重名 + 未知名
        ├── builtin/FileToolsTest.java（ReadFileToolTest/WriteFileToolTest/ListDirToolTest，实现级明确）
        ├── builtin/ShellToolsTest.java
        ├── builtin/HttpToolsTest.java
        ├── AnnotatedMethodToolAdapterTest.java
        └── mcp/McpClientServiceTest.java、McpToolAdapterTest.java

axion-cli/                                     # 003 交付物改造
└── src/main/java/com/axion/cli/CliAgentConfiguration.java
    # FR-7：工具集空 Map → ToolRegistry；RestClient（不变量 9）+ NotifyTools/Registry 显式 @Bean；
    #       PermissiveSandbox @Bean（javadoc 标注 24 节替换）
```

**Structure Decision**: 无新模块——CLAUDE.md 9 模块结构不变。axion-tool 内部按领域子包（builtin/mcp，004 notify 先例 + 2026-09-05 拍板"模块即层、子包按领域"口径）。唯一前序改造点 CliAgentConfiguration（003 FR-10 既定"第 20 节替换"口径）。

## Complexity Tracking

> 仅当 Constitution Check 有违规需豁免时填写——本节无违规，留空。

无。
