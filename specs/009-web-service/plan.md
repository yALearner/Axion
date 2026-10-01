# Implementation Plan: 009-web-service（REST API + 只读管理平台）

**Branch**: `009-web-service` | **Date**: 2026-09-08 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/009-web-service/spec.md`（需求文档 docs/requirements/009-web-service.md，修订说明 ①~⑥ 已钉死口径）

## Summary

补上能力五（US-5，五大能力最后一块拼图）：六个薄 Controller + 10 端点（统一前缀 /api/v1，与 CLI 共享 AgentService.process——宪法 VIII）；GlobalExceptionHandler 扩展（双信封拍板 B：成功 ApiResponse / 错误 ErrorResponse 单出口 + ⑨a 503 收紧 + 504 + 500 不泄漏）；serve 命令真启动（8080 + virtual thread + 排除 Spring AI eager 装配——单 key 可启动）；防呆限制（32KB/100 条）；springdoc-openapi；管理平台 v1 只读（Vue3+Vite + frontend-maven-plugin 拍板 B + SPA 回落 + axion-admin-ui 风格 skill）。

## Technical Context

**Language/Version**: Java 21（宪法 VII：请求在 virtual thread 直进直出）+ Vue 3 + Vite（前端，与 website/ 同栈）

**Primary Dependencies**: spring-boot-starter-web（web 模块核实补缺）、`springdoc-openapi-starter-webmvc-ui`（新）、`frontend-maven-plugin`（新，构建拍板 B）、FutureTask（JDK 原生，60s 超时无自建线程池）；测试：@WebMvcTest + @SpringBootTest（spring-boot-starter-test）

**Storage**: 无新增表（会话归档复用 002 sessions 表；GET /memory 直连 LongTermMemoryStore）

**Testing**: `SessionApiControllerTest`（@WebMvcTest 切片：32KB→400 / 不存在→404 / process 恰调一次）、`GlobalExceptionHandlerTest`（映射/信封边界/⑨a 回归/500 不泄漏）、`WebSmokeIT`（真上下文 /health /info /profiles /tools 可达 + JPA 扫描红线）、SessionManager 回归（⑨b 重启恢复/最新可见）+ 全量 `mvn clean verify`

**Target Platform**: Windows（开发本机）与 Linux（部署目标）；前端产物随 fat JAR

**Project Type**: Maven 多模块（9 模块不动：Controller 落 axion-web/api，ServeCommand 改造落 axion-cli，配置落 axion-boot，前端工程 axion-web/src/main/frontend）

**Performance Goals**: 端点本身零业务逻辑（委托核心层）；60s 超时上限；virtual thread 高并发下同步阻塞无瓶颈

**Constraints**: 宪法 VIII（与 CLI 同引擎）、VII（同步直进直出）、V（审计零新增）、II（禁用 Spring AI eager 自动装配）；交付清单白名单；双信封契约（拍板 B）；⑨ 四维修正（503 收紧/findById 一致性/504 任务体语义/Session 并发面核实）

**Scale/Scope**: 六 Controller + 5 异常类 + 1 枚举补值 + 1 skill + 前端工程；4 处改造点（GlobalExceptionHandler 扩展、ErrorCode 补值、ServeCommand 真启动、SessionManager 补 findById）；无新表

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 原则 | 本 feature 关系 | 结论 |
|------|---------------|------|
| I 自实现 ReAct Loop | 不涉及（Web 走 AgentService 既有引擎） | ✅ |
| II Spring AI 只用两件事 | **涉及**：autoconfigure.exclude 排除 OpenAiAutoConfiguration/DashScopeAutoConfiguration——禁用 eager 模型自动装配（16 节同招） | ✅ |
| III Provider 显式映射 | GET /info 读 ProviderService 既有显式映射状态 | ✅ |
| IV 一个目录 = 一个 Agent | 不涉及 | ✅ |
| V 审计表 Day One 写入 | 零新增审计——Web 触发走 AgentService 内部既有 llm_calls/tool_invocations | ✅ |
| VI Sandbox 接口先行 | 不涉及 | ✅ |
| VII 同步执行模型 | virtual thread + 同步阻塞直进直出；60s 超时 FutureTask（JDK 原生，无自建线程池）；不引入 WebFlux | ✅ |
| VIII 三种触发源共用一个引擎 | **本 feature 主体**：Web（人推）汇入 AgentService.process；⑨d 三触发源同场 Session 并发面实施前核实 | ✅ |
| IX Tool 模块三合一 | 不涉及 | ✅ |

无违反 → Complexity Tracking 不需要。

## Project Structure

### Documentation (this feature)

```text
specs/009-web-service/
├── plan.md              # 本文件（/speckit-plan 产物）
├── research.md          # Phase 0 裁决记录（修订说明 ①~⑥ 的裁决与备选）
├── data-model.md        # Phase 1 数据模型（无新表；双信封/异常映射/端点契约）
├── quickstart.md        # Phase 1 验证指南（harness 跑法 + serve 实跑人工项）
├── contracts/           # Phase 1 接口契约
│   └── rest-api.md
└── tasks.md             # Phase 2（/speckit-tasks，非本命令产物）
```

### Source Code (repository root)

```text
axion-web/src/main/java/com/axion/web/api/
├── SessionApiController.java       # 本节新增（4 端点）
├── AgentApiController.java         # 本节新增（invoke，三元组 ("web","invoke",name)）
├── ProfileApiController.java       # 本节新增（GET /profiles）
├── MemoryApiController.java        # 本节新增（GET /memory——直连 LongTermMemoryStore）
├── ToolApiController.java          # 本节新增（GET /tools）
├── SystemApiController.java        # 本节新增（GET /health /info）
├── InvalidRequestException.java    # 本节新增
├── SessionNotFoundException.java   # 本节新增
├── ResourceNotFoundException.java  # 本节新增
├── ProviderUnavailableException.java # 本节新增
├── AgentTimeoutException.java      # 本节新增
├── GlobalExceptionHandler.java     # 地基类扩展（双信封 + 504 + 500 不泄漏 + ⑨a 收紧）
├── ErrorCode.java                  # 地基类补 GATEWAY_TIMEOUT(504)
├── ApiResponse.java / ErrorResponse.java / ServiceUnavailableException.java  # 地基保留不动（拍板 B）

axion-web/src/main/frontend/       # 本节新增（Vue3+Vite 只读管理台五页）
axion-web/src/main/resources/static/admin/   # npm build 产物（Spring 托管）
axion-web/pom.xml                  # +web starter（如缺）+springdoc+frontend-maven-plugin+skip 配置
axion-core/.../SessionManager.java # 002 交付物改造：补 findById（⑨b 一致性核实前置）
axion-cli/.../ServeCommand.java    # 003 交付物改造：web(SERVLET) 真启动
axion-boot/src/main/resources/application.yaml  # +server.port +autoconfigure.exclude
.claude/skills/axion-admin-ui/SKILL.md  # 本节新增（风格 skill + 双信封请求封装）
```

**Structure Decision**: 沿用 9 模块与既有包结构（Controller 落 axion-web/api——技术方案 §7.1 明文；前端工程落 axion-web/src/main/frontend——课件点名）。不新建模块、不改依赖方向。
