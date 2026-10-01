# Implementation Plan: 007-sandbox（Sandbox 三层白名单）

**Branch**: `007-sandbox` | **Date**: 2026-09-06 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/007-sandbox/spec.md`（需求文档 docs/requirements/007-sandbox.md，修订说明 ①~⑦ 已钉死口径）

## Summary

把 002 砌好的 `Sandbox.enforce(SandboxAction)` 接口墙后面填上核心阶段第一档实现：`WhitelistSandbox` 三层白名单校验（文件路径 normalize+前缀匹配 / Shell 首 token / HTTP 域名点号边界通配），三块 `@ConfigurationProperties` 配置（file/shell/http），装配处从 `PermissiveSandbox` 换成 `WhitelistSandbox` 并删除前者。七工具（005 已接线 enforce）、`Sandbox` 接口、`ToolExecutor` 审计路径零改动——失败复用既有审计（success=false + error_message）。验收 harness 按「测绕过不测放行」组织（课件 24 §四）。

## Technical Context

**Language/Version**: Java 21（宪法 VII 虚拟线程）

**Primary Dependencies**: JDK 原生（`java.nio.file.Path`、`java.net.URI`、`String.split`——零新第三方依赖）；Spring Boot `@ConfigurationProperties`（3.x 既有）；SLF4J 日志（既有）；JUnit 5 + Mockito（既有测试栈）

**Storage**: 无新增（不建表；审计复用 002 已交付的 `tool_invocations`）

**Testing**: JUnit 5 + Mockito——`WhitelistSandboxTest` 四组（文件路径/Shell 命令/HTTP 域名/接线回归）+ `CliAgentConfigurationTest` 增补 + 工具接线回归（mock 底层执行器 verify never）+ 全量 `mvn clean verify`

**Target Platform**: Windows（开发本机，⑦b 大小写归一）与 Linux（部署目标，维持大小写敏感）双口径

**Project Type**: Maven 多模块（9 模块不动，改动只在 axion-tool 新增 + axion-cli 装配替换）

**Performance Goals**: enforce 校验微秒级纯内存操作（Path 归一/URI 解析/流式前缀匹配），相对 LLM 秒级延迟可忽略——无额外性能目标

**Constraints**: 宪法 VI（接口先行，enforce(SandboxAction) 不改）、V（审计 day one，复用既有不新增）、VII（同步阻塞）、IX（axion-tool 三合一）；交付清单白名单（超出即停）；上层零改动（七工具/Sandbox 接口/ToolExecutor 逐字节一致）

**Scale/Scope**: 核心阶段单实例；一个实现类 + 三配置类 + 一处装配替换 + 一个测试类增补；无新表、无新依赖、无新模块

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 原则 | 本 feature 关系 | 结论 |
|------|---------------|------|
| I 自实现 ReAct Loop | 不涉及（002 已交付，零改动） | ✅ |
| II Spring AI 只用两件事 | 不涉及 Spring AI 调用路径 | ✅ |
| III Provider 显式映射 | 不涉及 | ✅ |
| IV 一个目录 = 一个 Agent | 不涉及 | ✅ |
| V 审计表 Day One 写入 | FR-007：违规复用 ToolExecutor 既有审计落 `tool_invocations`（success=false + error_message），不新增审计逻辑、不建新表 | ✅ |
| VI 不使用 SecurityManager / Sandbox 接口先行 | **本 feature 主体**：实现 002 已定死的 `enforce(SandboxAction)` 第一档 `WhitelistSandbox`；不碰接口签名 | ✅ |
| VII 同步执行模型 | NFR-001：全程同步阻塞，无 Reactor/CompletableFuture | ✅ |
| VIII 三种触发源共用一个引擎 | 不涉及（沙箱与入口无关） | ✅ |
| IX Tool 模块三合一 | WhitelistSandbox + 三配置类落 axion-tool（com.axion.tool），不拆新模块 | ✅ |

无违反 → Complexity Tracking 不需要。

## Project Structure

### Documentation (this feature)

```text
specs/007-sandbox/
├── plan.md              # 本文件（/speckit-plan 产物）
├── research.md          # Phase 0 裁决记录（修订说明 ①~⑦ 的裁决与备选）
├── data-model.md        # Phase 1 数据模型（无新表；三配置契约与内部不可变状态）
├── quickstart.md        # Phase 1 验证指南（harness 跑法 + 集成验证人工项）
├── contracts/           # Phase 1 接口契约
│   └── sandbox-whitelist.md
└── tasks.md             # Phase 2（/speckit-tasks，非本命令产物）
```

### Source Code (repository root)

```text
axion-tool/src/main/java/com/axion/tool/
├── Sandbox.java                    # 002 已交付，零改动（接口墙）
├── SandboxAction.java              # 002 已交付，零改动
├── SandboxViolationException.java  # 002 已交付，零改动
├── PermissiveSandbox.java          # 005 交付 → 本节删除（⑦/FR-006）
├── WhitelistSandbox.java           # 本节新增（唯一实现）
├── FileSandboxProperties.java      # 本节新增（@ConfigurationProperties record）
├── ShellSandboxProperties.java     # 本节新增
├── HttpSandboxProperties.java      # 本节新增
└── builtin/                        # 005 已交付七工具，零改动

axion-cli/src/main/java/com/axion/cli/
└── CliAgentConfiguration.java      # 本节改造：sandbox() @Bean 替换 + @EnableConfigurationProperties

axion-tool/src/test/java/com/axion/tool/
└── WhitelistSandboxTest.java       # 本节新增（四组 harness）
axion-cli/src/test/java/com/axion/cli/
└── CliAgentConfigurationTest.java  # 本节增补（装配断言）
```

**Structure Decision**: 沿用 9 模块与既有包结构（com.axion.tool 单包 flat，007 交付物全部落 axion-tool；装配改造点落 axion-cli——003/005 先例）。不新建模块、不改依赖方向。
