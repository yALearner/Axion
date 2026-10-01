# Tasks: 009-web-service（REST API + 只读管理平台）

**Input**: Design documents from `specs/009-web-service/`（plan.md / spec.md / research.md / data-model.md / contracts/ / quickstart.md）

**Prerequisites**: 需求文档 docs/requirements/009-web-service.md（修订说明 ①~⑥ 已钉死口径——实施必须逐条对照，尤其拍板 B 双信封/B 构建与四维修正 ⑨a~d）

## Format: `[ID] [P?] [Story] Description`（[P] 可并行；文件路径精确到包）

## Phase 1: Setup（前序现状核对）

**Purpose**: H0 依赖检查——确认前序交付物与需求文档「现状确认」一致（机械 grep，不得跳过）

- [X] T001 前序现状核对：grep 确认地基 5 件（ApiResponse/ErrorResponse/ErrorCode/GlobalExceptionHandler/ServiceUnavailableException 在 axion-web/src/main/java/com/axion/web/api/）、`ServeCommand` 占位（web(NONE) + 占位输出，axion-cli/src/main/java/com/axion/cli/ServeCommand.java）、`SessionManager` 公开方法仅 getOrCreate 无 findById、application.yaml 已有 `spring.threads.virtual.enabled: true` 但无 `server.port`/`autoconfigure.exclude`；核实 axion-web/pom.xml 的 web starter/springdoc/test 依赖缺哪些（实施时补）——与需求文档「现状确认（2026-09-08 实测）」逐项一致

## Phase 2: Foundational（异常类 + 前序改造点）

**Purpose**: 所有 US 的共享前置——异常类、枚举补值、SessionManager.findById（⑨b）、Session 并发面核实（⑨d）

- [X] T002 [P] 创建 5 异常类于 axion-web/src/main/java/com/axion/web/api/：`InvalidRequestException`、`SessionNotFoundException`、`ResourceNotFoundException`、`ProviderUnavailableException`、`AgentTimeoutException`——全部 RuntimeException 子类，javadoc 注明映射状态码与语义（互不依赖可并行）
- [X] T003 扩展 `ErrorCode`（axion-web/src/main/java/com/axion/web/api/ErrorCode.java）：补 `GATEWAY_TIMEOUT(504)`（地基 4 值扩 5 值）
- [X] T004 `SessionManager` 补 `Optional<Session> findById(String)`（axion-core/src/main/java/com/axion/core/SessionManager.java，002 改造点 a）——**先 H3 核实 002 的持久化时机**（getOrCreate 内「库中命中则恢复」的实现路径），findById 复用同款恢复逻辑；缓存/库不一致 → 停下报告（⑨b）
- [X] T005 ⑨d 核实：读 002 的 `Session`/`ReActLoop` 代码判断并发语义（消息列表是否线程安全、同 Session 并发 process 是否安全）；**无保护 → 停下报告**，按预案补 per-session 最小互斥（008 per-task 锁同款）后继续

**Checkpoint**: 异常类/枚举/改造点就位，⑨b/⑨d 核实结论明确

---

## Phase 3: User Story 1 - 业务系统连续对话 (Priority: P1) ★ MVP

**Goal**: SessionApiController 4 端点（创建/发消息/查历史/归档）+ 32KB/100 条防呆 + 薄 Controller 机器证据

**Independent Test**: `SessionApiControllerTest`（@WebMvcTest 切片）全绿

### Tests for User Story 1（先行，确保 FAIL 再实现）

- [X] T006 [US1] 创建 `SessionApiControllerTest` 于 axion-web/src/test/java/com/axion/web/api/SessionApiControllerTest.java（@WebMvcTest + mock AgentService/SessionManager）：① 超 32KB → 400；② Session 不存在 → 404；③ 正常请求 `agentService.process` 恰被调一次（薄 Controller 机器证据——Controller 没夹带私货）；④ 历史超 100 条只返回最近 100 条。此时 Controller 不存在——编译失败即 red 证据

### Implementation for User Story 1

- [X] T007 [US1] 创建 `SessionApiController` 于 axion-web/src/main/java/com/axion/web/api/SessionApiController.java（需求文档骨架逐字对照）：POST /sessions（SessionManager 创建，返回 ApiResponse 含 session_id）、POST /{id}/messages（32KB 校验 → findById → agentService.process → MessageResponse；**60s 超时包装 FutureTask.get(60s)+虚线程——⑨c**）、GET /{id}（历史最近 100 条）、DELETE /{id}（归档）；DTO `MessageRequest`/`MessageResponse` record 同文件或同包；Controller 只做校验/包装/错误三件事

**Checkpoint**: T006 全部转绿，US1 独立可验证

---

## Phase 4: User Story 2 - 一次性无状态调用 (Priority: P1)

**Goal**: AgentApiController invoke（一次性 Session 三元组 ("web","invoke",name) 跑完不缓存）

**Independent Test**: `AgentApiControllerTest` 全绿

### Tests for User Story 2

- [X] T008 [US2] 创建 `AgentApiControllerTest` 于 axion-web/src/test/java/com/axion/web/api/AgentApiControllerTest.java（@WebMvcTest）：invoke 走 process 恰一次、三元组 `("web","invoke",name)` 断言（verify getOrCreate 参数）、Agent 不存在 → 404、成功返回 ApiResponse 信封

### Implementation for User Story 2

- [X] T009 [US2] 创建 `AgentApiController` 于 axion-web/src/main/java/com/axion/web/api/AgentApiController.java：POST /agents/{name}/invoke——ProfileRegistry.findByName 校验（不存在 → ResourceNotFoundException）、一次性 Session（三元组 ("web","invoke",name) 跑完不缓存，⑨ 实现级明确）、60s 超时包装（同 T007 ⑨c）

---

## Phase 5: User Story 3 - 信息查询与系统状态 (Priority: P2)

**Goal**: Profile/Memory/Tool/System 四个查询 Controller（5 端点）+ springdoc

**Independent Test**: `WebSmokeIT`（US5 阶段真上下文）+ 各 Controller 编译就位

### Implementation for User Story 3

- [X] T010 [US3] 创建 `ProfileApiController`（GET /profiles → ProfileRegistry.list()）、`MemoryApiController`（GET /memory → **LongTermMemoryStore.load() 直连**，实现级明确不扩门面）、`ToolApiController`（GET /tools → ToolRegistry）、`SystemApiController`（GET /health 回 ok、GET /info 含各 Provider 状态）——四个文件于 axion-web/src/main/java/com/axion/web/api/（空表返回空列表不报错）
- [X] T011 [US3] springdoc-openapi 集成：axion-web/pom.xml 增 `springdoc-openapi-starter-webmvc-ui` 依赖（版本 H3 核实兼容线）；零代码——/swagger-ui 自动生成

---

## Phase 6: User Story 4 - 错误统一可预期 (Priority: P2) ★ 验收场景

**Goal**: GlobalExceptionHandler 扩展（双信封拍板 B + ⑨a 收紧 + 504 + 500 不泄漏）

**Independent Test**: `GlobalExceptionHandlerTest` 全绿（含最值钱回归 + 信封边界 + ⑨a 回归）

### Tests for User Story 4（先行，确保 FAIL 再实现——最值钱回归实现前写）

- [X] T012 [US4] 创建 `GlobalExceptionHandlerTest` 于 axion-web/src/test/java/com/axion/web/api/GlobalExceptionHandlerTest.java：① 每类异常映射约定状态码（400/404/503/504/500）；② **信封边界**：错误响应均为 ErrorResponse（errorCode/message/timestamp）、成功响应均为 ApiResponse——拍板 B 漂移防线；③ **最值钱回归兼 ⑨a 回归（需求文档原文，一测双钉）**：`IllegalStateException("jdbc:sqlite:/data/axion.db connect failed")` → 500（非 503——⑨a 收紧后业务校验异常走 500 兜底）+ `$.errorCode=500` + `$.message=内部错误` + 响应不含 "jdbc:sqlite"（内幕不泄漏）。此时映射未扩展——red 证据

### Implementation for User Story 4

- [X] T013 [US4] 扩展 `GlobalExceptionHandler`（axion-web/src/main/java/com/axion/web/api/GlobalExceptionHandler.java，地基类保留 ErrorResponse 形态）：新增映射——404（SessionNotFoundException/ResourceNotFoundException）、503（**仅** ProviderUnavailableException + ServiceUnavailableException——⑨a）、504（AgentTimeoutException）、400（InvalidRequestException）、500 兜底「内部错误」不泄漏；保留地基既有 IllegalArgumentException→400、MethodArgumentNotValidException→400、NoResourceFoundException→404 映射

---

## Phase 7: User Story 5 - serve 单 key 启动 (Priority: P2)

**Goal**: serve 真启动（8080 + virtual thread + 排除 eager 装配）+ WebSmokeIT 真上下文

**Independent Test**: `WebSmokeIT` 全绿 + 人工项干净机器单 key 实跑

### Implementation for User Story 5

- [X] T014 [US5] application.yaml（axion-boot/src/main/resources/application.yaml）增 `server.port: 8080` + `spring.autoconfigure.exclude` 排除两装配类——**H3 核实全限定名**（mvn dependency:tree 锁定 spring-ai 版本下 OpenAiAutoConfiguration/DashScopeAutoConfiguration 的包路径，课件原话）
- [X] T015 [US5] 改造 `ServeCommand`（axion-cli/src/main/java/com/axion/cli/ServeCommand.java）：`web(WebApplicationType.SERVLET)` 真启动、删除占位输出、javadoc 更新（003 交付物改造点）
- [X] T016 [US5] 创建 `WebSmokeIT` 于 axion-web/src/test/java/com/axion/web/api/WebSmokeIT.java（@SpringBootTest(classes=AxionApplication) 真上下文，不依赖模型）：GET /health /info /profiles /tools 真实可达（状态码 200 + 信封断言）——**JPA repository 扫描红线**（18 节「Found 0 repositories」坑复发第一时间红）；@DynamicPropertySource 补全 provider 整元素（008 坑表）

---

## Phase 8: User Story 6 - 只读管理台 + 风格 skill (Priority: P2)

**Goal**: axion-admin-ui skill + Vue3+Vite 五页 + frontend-maven-plugin（拍板 B）+ SPA 回落

**Independent Test**: skill 文件核对 + `mvn package` 含前端产物 + 人工项 /admin 核对

### Implementation for User Story 6

- [X] T017 [US6] 创建 `.claude/skills/axion-admin-ui/SKILL.md`：首页设计 token（值抄 website/.vitepress/theme/custom.css——深色 #000000/#111111/#1a1a1a、分隔 #222222、主色橙 #f97316/#ea6a00/#c2550a 仅强调、文字 #f5f5f5/#a3a3a3/#666666、字体 Inter + JetBrains Mono）+ 工程约定（base '/admin/'、产物 static/admin、SPA 回落、只调 /api/v1）+ 三态规范（空数据/加载中/错误占位）+ 响应式 + 验收清单 + **双信封统一请求封装**（成功取 ApiResponse.data、错误取 ErrorResponse.errorCode/message——⑨ P2 落地，30 节复用）；不引外部 skill
- [X] T018 [US6] 前端工程 axion-web/src/main/frontend/（Vue3+Vite，按 T017 skill 的提示词生成）：左侧导航五页（会话列表/Profile 列表/Tool 列表/长期记忆/运行状态）调五个 GET 端点、只读无写按钮、错误展示 message、vite base '/admin/' 产出 static/admin/
- [X] T019 [US6] frontend-maven-plugin 配置（axion-web/pom.xml，拍板 B）：`npm ci && npm run build` 绑进 mvn package；**`-Dskip.npm` 跳过参数**（后端迭代不背前端构建）；**node 版本锁进 pom 并与 .nvmrc 同步**（⑨ P2 落地）；内网离线构建注记
- [X] T020 [US6] SPA 回落配置：`/admin/**` 未命中路径回落 `admin/index.html`（WebMvcConfigurer 或 addResourceHandlers；`GET /api/v1/**` 不受影响）

---

## Phase 9: Polish（收尾与全量门禁）

**Purpose**: 全量回归、收尾 DoD 证据、人工项清单

- [X] T021 全量门禁：`mvn clean verify` 全绿（**不写死用例数**——007 ⑦e 口径；静态门禁一并全绿）+ `mvn package` 验证 fat JAR 含前端产物（frontend-maven-plugin 拍板 B 的证据）
- [X] T022 收尾 DoD 证据 + 人工项清单：① git diff 目检地基 5 件零删除（拍板 B）+ 前序公共接口仅 4 处改造点（GlobalExceptionHandler/ErrorCode/ServeCommand/SessionManager.findById）；② 对照 quickstart.md 输出机器判卷结论与**剩余人工项清单**（serve 实跑 curl 四命令 + /admin 只读核对 + /swagger-ui + 干净机器单 key 启动——人工项不自动执行，如实列待办）；③ 按 axion-spec gates.md 收尾 DoD 七项出具证据（宪法不变量 ⑥ 条：无 Reactor/CompletableFuture/自建线程池——FutureTask+虚线程为 JDK 原生；Web 请求走 AgentService 既有审计）

---

## Dependencies & Execution Order

### Phase Dependencies

- Setup（Phase 1）→ Foundational（Phase 2，含 ⑨b/⑨d 核实——**核实不过则停**）→ US1~US4（Phase 3~6）→ US5（Phase 7，依赖 US3 的 T010 编译）→ US6（Phase 8）→ Polish（Phase 9）
- US4 的 GlobalExceptionHandlerTest 依赖 Foundational 的异常类（T002）与枚举（T003）

### User Story Dependencies

- **US1 (P1)**: 依赖 Foundational——本节核心交付（4 端点 + 防呆），MVP
- **US2 (P1)**: 依赖 Foundational——invoke 三元组钉死
- **US3 (P2)**: 依赖 Foundational——4 查询 Controller + springdoc
- **US4 (P2)**: 依赖 Foundational（T002/T003）——异常单出口扩展
- **US5 (P2)**: 依赖 US3（Controller 就位）——serve 真启动 + WebSmokeIT
- **US6 (P2)**: 依赖 US3（五端点数据源）——管理台 + skill

### Within Each User Story

- 测试先行（T006/T008/T012 先行 red；最值钱回归实现前写——课件 §四原文）
- 红了当场修，不攒到最后（gates.md 任务级 DoD）

### Parallel Opportunities

- T002 五异常类可并行（5 文件）；T003 独立
- T006/T008/T012 三个测试文件可并行写（不同文件）
- T010 四 Controller 可并行（4 文件）；T011 独立
- T017（skill）与 T018（前端）顺序（skill 先行约束生成提示词）

## Implementation Strategy

### MVP First（US1 优先）

1. T001 现状核对 → T002~T005 基础与核实 → T006 测试先行（red）→ T007 实现 → **STOP and VALIDATE**：`mvn test -pl axion-web -am` 全绿
2. US1 即「业务系统连续对话」——可独立演示 curl 建会话+发消息

### Incremental Delivery

1. US1/US2 → 两个写路径（连续对话 + 无状态调用）成立
2. US3 → 五查询端点 + springdoc（管理台数据源）
3. US4 → 异常单出口 + 最值钱回归（验收场景）
4. US5 → serve 真启动 + 真上下文可达（WebSmokeIT 红线）
5. US6 → 管理台 + 风格 skill（第二个交付物）
6. Polish → 全量门禁 + DoD + 人工项

### Parallel Team Strategy

单人顺序执行即可；如多人：A 做 T002/T003 基础、B 写 T006/T008/T012 测试、汇合后实现。

## Notes

- [P] tasks = 不同文件无依赖；[Story] label 映射 spec.md user story
- 每个 task 开始前：宪法 9 条速查（java-spring-init constitution-checklist.md）+ 停止清单预检（gates.md §停止清单）
- 交付清单白名单：六 Controller + 5 异常类 + ErrorCode 补值 + GlobalExceptionHandler 扩展 + ServeCommand 改造 + SessionManager.findById + 前端工程 + axion-admin-ui skill——**清单之外的对外概念一律先停**（停止清单第 1 条）
- 已定字面量保真：双信封字段名（code/message/data/timestamp、errorCode/message/timestamp）、地基类零删除（拍板 B）、10 端点路径（停止清单第 2 条）
- 测试方法名英文（gates.md H1/H5）
- 不自动 commit / push / 运行 package.sh——同步时机由用户决定
