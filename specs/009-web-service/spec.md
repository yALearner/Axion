# Feature Specification: Web Service（能力五：REST API + 只读管理平台）

**Feature Branch**: `009-web-service`

**Created**: 2026-09-08

**Status**: Draft

**Input**: 需求文档 docs/requirements/009-web-service.md（课件第 26 节：Web Service 与第一版管理平台；修订说明 ①~⑥ 口径全钉——拍板 B 双信封/B 构建 + 四维修正 ⑨ 落位）

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 业务系统连续对话（Priority: P1）

业务系统先 `POST /sessions` 建会话，再多次 `POST /sessions/{id}/messages` 发消息（每次走完整 ReAct 循环返回答复），`GET /sessions/{id}` 查历史（最多最近 100 条）、`DELETE /sessions/{id}` 归档。Controller 只做校验/包装/错误三件事，发消息走 `agentService.process`——与 CLI 完全同一入口（需求文档场景一，课件 §一/§二）。

**Why this priority**: 业务系统集成的最常用路径（课件原文点名）；「Controller 薄、与 CLI 同引擎」是本节架构第一原则——写代码时最容易被「顺手在 Controller 里加点逻辑」破坏。

**Independent Test**: `SessionApiControllerTest`（@WebMvcTest 切片）：32KB→400 / Session 不存在→404 / `agentService.process` 恰被调一次（薄 Controller 的机器证据）。

**Acceptance Scenarios**:

1. **Given** 业务系统发 POST /sessions，**When** 创建成功，**Then** 返回 ApiResponse 信封含新 session_id
2. **Given** 已建会话，**When** POST /sessions/{id}/messages 发消息（≤32KB），**Then** 走 agentService.process 完整 ReAct 返回答复（ApiResponse 信封）——与 axion chat 同一入口（宪法 VIII）
3. **Given** 消息为空或超 32KB，**When** 发消息，**Then** 400 + ErrorResponse 信封（防呆不是治理）
4. **Given** 会话 id 不存在，**When** 发消息/查历史，**Then** 404 + ErrorResponse 信封
5. **Given** 历史超过 100 条，**When** GET /sessions/{id}，**Then** 只返回最近 100 条
6. **Given** 会话归档，**When** DELETE /sessions/{id}，**Then** 归档成功（复用 002 sessions 表语义）

---

### User Story 2 - 一次性无状态调用（Priority: P1）

不想管 Session 生命周期，直接 `POST /agents/{name}/invoke`：临时建一次性 Session、跑完 ReAct 返回。一次性 Session 三元组固定 `("web", "invoke", agentName)`、跑完不缓存（⑨ 实现级明确——审计表 session_id 可追溯且不污染长会话）。

**Why this priority**: 业务系统「把 Agent 当函数用」的路径；invoke 的会话身份若不定死，审计表会出现不定身份的行。

**Independent Test**: `AgentApiControllerTest`（@WebMvcTest）：invoke 走 process 恰一次、返回信封、三元组取值断言。

**Acceptance Scenarios**:

1. **Given** Agent 名存在，**When** POST /agents/{name}/invoke 带消息，**Then** 跑完 ReAct 返回答复（一次性 Session 三元组 ("web","invoke",name)）
2. **Given** Agent 名不存在，**When** invoke，**Then** 404 + ErrorResponse 信封
3. **Given** invoke 完成，**When** 查审计，**Then** llm_calls/tool_invocations 的 session_id 可追溯（三元组固定）

---

### User Story 3 - 信息查询与系统状态（Priority: P2）

`GET /profiles` 列 ProfileRegistry、`GET /tools` 列 ToolRegistry、`GET /memory` 返回 MEMORY.md 内容（直连 `LongTermMemoryStore.load()`——实现级明确：不扩 MemoryService 门面）、`GET /health` 回 ok、`GET /info` 含各 Provider 连通状态。

**Why this priority**: 管理台五页的数据源（课件：管理台没有自己的后端，所有数据来自 10 端点——顺带验证「API 是完备的对外通道」）；WebSmokeIT 的 JPA 扫描红线也在这里验证。

**Independent Test**: `WebSmokeIT`（@SpringBootTest 真上下文，不依赖模型）：/health /info /profiles /tools 真实链路可达——Bean 装配与 JPA repository 扫描（18 节「Found 0 repositories」坑复发第一时间红）。

**Acceptance Scenarios**:

1. **Given** 服务启动，**When** GET /health，**Then** 200 ok（ApiResponse 信封）
2. **Given** 服务启动，**When** GET /profiles /tools /memory，**Then** 各自返回当前注册内容（空表也返回空列表/空内容，不报错）
3. **Given** 服务启动，**When** GET /info，**Then** 含各 Provider 连通状态
4. **Given** OpenAPI 文档，**When** 访问 /swagger-ui，**Then** 自动生成的接口文档可见（springdoc 零手写）

---

### User Story 4 - 错误统一可预期（Priority: P2）★ 验收场景

所有异常经 GlobalExceptionHandler 单出口转标准错误 JSON：400（InvalidRequest）/404（Session 不存在、资源不存在）/503（**仅** ProviderUnavailableException + 地基 ServiceUnavailableException——⑨a 收紧：IllegalStateException 归 500 兜底，业务校验异常不被报成「Provider 故障」）/504（Agent 调用 60s 上限）/500 兜底统一话术「内部错误」**不泄漏异常 message**（内部细节只进日志）。双信封边界：成功 = ApiResponse、错误 = ErrorResponse，且只经 GlobalExceptionHandler 产出（拍板 B 契约漂移防线）。

**Why this priority**: 业务系统对接时不用逐个端点猜错误格式（课件 §二）；「门面的分寸」是最值钱回归；503 映射收紧（⑨a）防止语义污染。

**Independent Test**: `GlobalExceptionHandlerTest`：每类异常映射约定状态码 / 信封边界（错误均 ErrorResponse、成功均 ApiResponse）/ ⑨a 回归（IllegalStateException→500 非 503）/ 500 不含内部异常 message。

**Acceptance Scenarios**:

1. **Given** 各类异常（400/404/503/504），**When** 任何端点抛出，**Then** 响应体均为 ErrorResponse 信封（errorCode/message/timestamp）且状态码符合约定映射
2. **Given** 业务校验类 IllegalStateException（如配置错误），**When** 请求路径抛出，**Then** 500 而非 503（⑨a：语义不污染）
3. **Given** 内部异常（连接串等内幕），**When** 兜底处理，**Then** 500 + message 统一为「内部错误」、响应体不含内幕（最值钱回归）
4. **Given** Agent 调用超过 60 秒，**When** 超时，**Then** 504 + ErrorResponse；超时后任务体继续跑完（审计照常、不可强制中断——⑨c 明示）
5. **Given** 成功响应，**When** 任何端点返回，**Then** 均为 ApiResponse 信封（code/message/data/timestamp）

---

### User Story 5 - serve 单 key 启动（Priority: P2）

干净机器只配 `DEEPSEEK_API_KEY` 一个环境变量，`axion serve` 正常起在 8080：virtual thread 直进直出（已配）；`spring.autoconfigure.exclude` 排除 `OpenAiAutoConfiguration` + `DashScopeAutoConfiguration`（课件坑：Spring AI eager 装配急切实例化索要 spring.ai.openai.api-key 卡死 serve；宪法 II；排除类全限定名 H3 核实）。

**Why this priority**: 本节第一次真正让人跑 serve 常驻——不排掉 eager 装配会逼运营方配两个 key，卡死 31 节「干净机器 30 分钟部署」。

**Independent Test**: `ServeCommand` 真启动测试（web(SERVLET) 上下文起得来）；`WebSmokeIT` 覆盖真上下文；人工项干净机器单 key 实跑。

**Acceptance Scenarios**:

1. **Given** 只配 DEEPSEEK_API_KEY，**When** axion serve 启动，**Then** 起在 8080 不因缺 spring.ai.openai.api-key 失败
2. **Given** 请求进入，**When** 处理，**Then** 在 virtual thread 上同步直进直出（宪法 VII，不引入响应式）
3. **Given** Agent 调用，**When** 超 60 秒，**Then** 504（FutureTask.get(60s) + 虚线程承载，无自建线程池）

---

### User Story 6 - 只读管理台 + 风格 skill（Priority: P2）

`/admin` 静态前端（Vue3+Vite 与官网同栈同风格）：五页（会话/Profile/Tool/长期记忆/运行状态）调五个 GET 端点渲染、无任何写按钮、错误时展示错误信封 message；`frontend-maven-plugin` 构建绑进 mvn（拍板 B，含 `-Dskip.npm` 跳过参数 + node 版本锁定与 .nvmrc 同步）；SPA 回落 /admin/** → index.html；风格固化进 `.claude/skills/axion-admin-ui/SKILL.md`（设计 token/工程约定/三态/响应式/验收清单 + **内置双信封统一请求封装**——成功取 data、错误取 errorCode/message，页面不手写两套解析）。

**Why this priority**: 第二个交付物（课件）；管理台没有自己的后端 = 顺带验证 API 完备性；skill 是 30 节 Agent 管理页的复用资产。

**Independent Test**: 人工项 /admin 五页渲染核对（无写按钮、错误展示 message）；skill 文件存在且含 token/约定/请求封装/验收清单（文件级核对）。

**Acceptance Scenarios**:

1. **Given** serve 运行，**When** 访问 /admin，**Then** 五页渲染正常、无任何新建/编辑/删除按钮
2. **Given** 前端出错，**When** 页面请求失败，**Then** 展示错误信封的 message（经 skill 统一请求封装）
3. **Given** 刷新 /admin 子路由，**When** Spring 未命中静态资源，**Then** 回落 admin/index.html（GET /api/v1/** 不受影响）
4. **Given** mvn package，**When** 构建，**Then** 前端产物入 fat JAR（frontend-maven-plugin）；后端迭代可用 `-Dskip.npm` 跳过前端构建

---

### Edge Cases

- 消息恰为 32KB 边界：允许（>32KB 拒绝）；空消息拒绝
- 历史超 100 条：只返回最近 100 条
- Session 不存在 / Agent 不存在 → 404
- IllegalStateException（业务校验）→ 500 非 503（⑨a）
- Agent 调用超 60s → 504；超时后任务体继续跑完（⑨c 明示）
- invoke 一次性 Session 三元组固定 ("web","invoke",name)、跑完不缓存
- 空 ProfileRegistry/ToolRegistry/MEMORY.md → 空结果不报错
- SPA 子路由刷新 → 回落 index.html
- 502/504 重试叠加：客户端收到 504 后重试会叠加第二次完整 ReAct——幂等/限流归扩展阶段（⑨c）

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: 六 Controller + 10 端点（统一前缀 /api/v1）：SessionApiController（POST /sessions、POST /sessions/{id}/messages、GET /sessions/{id}、DELETE /sessions/{id}）、AgentApiController（POST /agents/{name}/invoke——一次性 Session 三元组固定 ("web","invoke",name) 跑完不缓存，⑨ 实现级明确）、ProfileApiController（GET /profiles）、MemoryApiController（GET /memory——直连 LongTermMemoryStore.load()，不扩 MemoryService 门面）、ToolApiController（GET /tools）、SystemApiController（GET /health、GET /info 含 Provider 状态）。Controller 薄：校验/包装/错误三件事之外不碰业务；发消息走 agentService.process（宪法 VIII）
- **FR-002**: 异常单出口（双信封拍板 B）：GlobalExceptionHandler 扩展（保留地基 ErrorResponse 形态）——404（SessionNotFoundException/ResourceNotFoundException）、503（**仅** ProviderUnavailableException + 地基 ServiceUnavailableException——⑨a 收紧）、504（AgentTimeoutException）、400（InvalidRequestException，消息空/超 32KB）、500 兜底「内部错误」不泄漏内幕；保留既有 IllegalArgumentException→400、NoResourceFoundException→404；ErrorCode 补 GATEWAY_TIMEOUT(504)；信封契约：成功 ApiResponse / 错误 ErrorResponse 且只经 GlobalExceptionHandler 产出
- **FR-003**: serve 命令落地：ServeCommand 改造 web(SERVLET) 真启动；server.port 8080；autoconfigure.exclude 排除 OpenAiAutoConfiguration + DashScopeAutoConfiguration（课件坑，H3 核实全限定名）；60s 超时 FutureTask.get(60s) + 虚线程承载（无自建线程池）；⑨c 超时后任务体继续跑完、不可中断、审计照常
- **FR-004**: 防呆限制：单条消息 32KB（超→400）；历史返回最近 100 条
- **FR-005**: springdoc-openapi 自动文档（/swagger-ui），pom 新增 springdoc-openapi-starter-webmvc-ui
- **FR-006**: 管理台 v1 只读：Vue3+Vite 五页调五 GET；无写按钮；frontend-maven-plugin 绑进 mvn（拍板 B：-Dskip.npm 跳过参数 + node 版本锁进 pom 与 .nvmrc 同步 + 内网离线构建注记）；vite base '/admin/' 产出 static/admin/；SPA 回落 /admin/** → index.html（/api/v1/** 不受影响）
- **FR-007**: 风格 skill .claude/skills/axion-admin-ui/SKILL.md：首页设计 token + 工程约定 + 三态规范 + 响应式 + 验收清单 + **内置双信封统一请求封装**（成功取 data、错误取 errorCode/message）；不引外部 skill
- **NFR-001**: 全程同步阻塞，请求在 virtual thread 直进直出；不引入 WebFlux/响应式（宪法 VII）
- **NFR-002**: 核心阶段不做：认证（内网假设）、SSE、WebSocket、限流、RBAC
- **NFR-003**: 门面分寸：500 兜底不含内部异常 message，细节只进日志

### Key Entities

- **ApiResponse**（地基已有，不改）：code/message/data/timestamp——成功信封
- **ErrorResponse**（地基已有，不改）：errorCode/message/timestamp——错误信封（拍板 B）
- **ErrorCode**（地基已有，补值）：BAD_REQUEST/NOT_FOUND/INTERNAL_ERROR/SERVICE_UNAVAILABLE + **GATEWAY_TIMEOUT(504)**
- **新增 5 异常类**：InvalidRequestException/SessionNotFoundException/ResourceNotFoundException/ProviderUnavailableException/AgentTimeoutException
- **六 Controller**：Session/Agent/Profile/Memory/Tool/System（axion-web/api）
- **SessionManager.findById**（改造点 a，⑨b 一致性核实前置）

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 10 端点全部可达且走 AgentService.process 统一入口（薄 Controller 机器证据：process 恰调一次）
- **SC-002**: 所有错误响应均为 ErrorResponse 信封、所有成功响应均为 ApiResponse 信封（信封边界测试钉死——拍板 B 漂移防线）
- **SC-003**: 最值钱回归全绿——500 响应不含内部异常 message（统一话术「内部错误」）；⑨a 回归：IllegalStateException→500 非 503
- **SC-004**: serve 单 key 启动（干净机器只配 DEEPSEEK_API_KEY，8080 正常起——人工项实跑）
- **SC-005**: WebSmokeIT 真上下文 /health /info /profiles /tools 可达（JPA repository 扫描红线）
- **SC-006**: /admin 五页只读渲染、/swagger-ui 可见（人工项核对）
- **SC-007**: 全量 mvn clean verify 全绿（不写死用例数，007 ⑦e 口径）

## Assumptions

- **前序交付物已实测就位**（2026-09-08）：AgentService.process/SessionManager（getOrCreate 三元组）/ProfileRegistry.list/ToolRegistry/LongTermMemoryStore.load 就位；axion-web 有地基 5 件（java-spring-init 产物）；ServeCommand 占位（web(NONE)）；spring.threads.virtual.enabled 已在 application.yaml——纯增量 + 4 处改造点
- **课件口径（用户拍板 2026-09-08）**：整体方案参考 26 节课件；与四文档冲突处以课件为准（002 先例）
- **拍板 B 双信封**：保留地基双信封（成功 ApiResponse/错误 ErrorResponse），契约漂移由测试钉死；**拍板 B 构建**：frontend-maven-plugin 绑进 mvn（含 skip 开关）
- **⑨ 四维修正**：503 映射收紧（IllegalStateException 归 500）；SessionManager.findById 缓存/库一致性实施前 H3 核实（⑨b）；504 任务体语义明示（⑨c）；三触发源同场 Session 并发面实施前核实（⑨d，无保护则补 per-session 互斥）
- **改造点**（经拍板）：GlobalExceptionHandler 扩展 + ErrorCode 补值 + ServeCommand 真启动 + SessionManager 补 findById——其余前序公共接口零改动
- **明确不做**：Agent 目录增删改（29/30 节）、Memory 写端点、Webhook、Prometheus、SSE/WebSocket、认证/限流/RBAC、管理台写操作、Tool describe 与调用历史、LLM call 历史/token 统计端点、调度管理端点（28 节）
- **排除类全限定名 H3 核实**：OpenAiAutoConfiguration/DashScopeAutoConfiguration 以 mvn dependency:tree 锁定的 spring-ai 版本为准（课件原话）
