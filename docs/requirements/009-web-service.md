# Web Service 模块设计文档

> 需求编号：009-web-service | 对应课件第 26 节《Web Service 与第一版管理平台 实现与代码讲解》、US-5（核心能力五，五大能力的最后一块拼图）
> 文档依据：`docs/TechnicalSolution.md` §7（权威设计源）、`docs/DemandAnalysis.md` §5.8/§13（Demo 四/五）、`docs/AiProgrammingGuide.md` §4.5（US-5 依赖前四）；CLAUDE.md「Web Service API」端点表
>
> 修订说明（2026-09-08）：① **课件口径（用户拍板）**：**整体方案参考 `D:\项目\` 第 26 节课件**（新版 PDF 已 PyMuPDF 提取，9 页全文复核）；与四文档冲突处以课件为准（002 先例延续）。② **信封拍板（B，用户拍板 2026-09-08）**：保留地基双信封形态——成功响应 `ApiResponse`（code/message/data/timestamp）、错误响应 `ErrorResponse`（errorCode/message/timestamp），**错误一律经 GlobalExceptionHandler 单出口产出**；技术方案 §7.1/课件「成功与错误共用一个信封」口径经用户拍板改为双信封（冲突记录：权威口径单一信封 vs 地基双信封——拍板按地基，零删除、零改造地基类）；**双信封的契约漂移防线（落地细节一）**：`GlobalExceptionHandlerTest` 断言「每个错误响应均为 ErrorResponse 信封、每个成功响应均为 ApiResponse 信封」——机器钉死两套信封各自的使用边界，新端点选错信封测试即红。③ **前端构建拍板（B，用户拍板）**：`frontend-maven-plugin` 把 `npm ci && npm run build` 绑进 `mvn package`（一条命令出 fat JAR；课件二选一之 B）；**skip 开关（落地细节二）**：pom 配好 `-Dskip.npm` 跳过参数，后端迭代跑 `mvn -Dskip.npm package` 不背前端构建——把 B 的唯一痛点（构建变慢）解掉。④ **形态错位适配**：课件叙述「ApiResponse 第 24 节随白名单端点已建于 axion-web」与 Axion 实际交付顺序错位——24 节（007-sandbox）无 Web 端点，ApiResponse/ErrorResponse/ErrorCode/GlobalExceptionHandler/ServiceUnavailableException 实为 java-spring-init 工程地基产物（javadoc 已注明）；按现状落位，叙述不照搬课件节号。⑤ **图名处置**：技术方案 §7 无既有图，本节设计文档图命名 `docs-web-service-flow.svg`。⑥ **实施前优化（2026-09-08 四维修正分析：扩展性/可靠性/延展性/稳定性）**：a. **503 映射收紧**——课件骨架 `{IllegalStateException, ProviderUnavailableException}`→503 全域映射与项目现状冲突（001 ConfigLoader 校验/006 mem0 缺凭证/008 非法 zone 均抛 IllegalStateException 作业务校验）——语义污染：配置错误被报成「Provider 故障」，业务系统按 503 做重试/降级方向全错；收紧为 503 只映射 `ProviderUnavailableException` + 地基 `ServiceUnavailableException`，IllegalStateException 归 500 兜底（内幕照旧不泄漏）；b. **`SessionManager.findById` 的缓存/库一致性**——改造点 a 依赖「库里恢复历史」语义，缓存内实例与库的同步时机须实施前 H3 核实（002 持久化时机），补回归「重启后 findById 恢复历史」「发消息后 findById 可见最新」；c. **504 超时后任务体语义**——`FutureTask.get(60s)` 超时后 ReAct 任务体继续跑完（LLM 调用不可强制中断）：审计照常落账、历史继续追加、费用继续产生；客户端重试会叠加第二次完整 ReAct——文档明示「超时后任务体不可杀」，幂等/限流归扩展阶段；d. **三触发源第一次同场 → Session 并发面**——serve 常驻后 Web 并发请求 + 008 钟推可能同时 `process` 同一 Session（scheduler 三元组会话长期复用），002 的 `Session.append`/ReActLoop 并发语义在 CLI 单线程时代从未被考验——实施前核实，无保护则本节补 per-session 最小互斥（008 per-task 锁同款），不得默认「应该没事」；P2 落位——invoke 会话三元组钉死（FR-1）、admin-ui 内置双信封请求封装（FR-7）、node 版本锁定（FR-6）。

## 背景与价值

到上一节为止，底座的能力全部就位：会调模型、会思考、有入口、能推送、能干活、记得住、有安全边界、还能到点自动跑——但这些能力目前**只有 CLI 一个对外出口**，业务系统没法用（课件 §一）。

本节补上能力五：**Web Service 把内部能力包装成 REST API**，顺带做出第一版管理平台。没有它，Axion 只是一个 CLI 工具；有了它，任何能发 HTTP 请求的业务系统都能把 Agent 接进自己的流程（课件 §一、技术方案 §7）。这是 Axion 区别于偏个人定位产品的关键能力（需求文档 §5.8），也是 US-5 排在最后的原因——依赖前四个能力全部就绪，不是不重要，恰恰相反（编程指南 §4.5）。

管理平台是这节的第二个交付物：一个跑在 `/admin` 的静态前端，调这 10 个端点，把会话、Profile、Tool、记忆、状态渲染成能看的页面。它**没有自己的后端**——所有数据都来自这 10 个端点，这也顺带验证了「API 是完备的对外通道」这件事本身（课件 §一）。

## 用户场景

**场景一（业务系统最常用）：连续对话**——业务系统先 `POST /sessions` 建会话，再多次 `POST /sessions/{id}/messages` 发消息（每次都走完整 ReAct 循环返回答复），`GET /sessions/{id}` 查历史、`DELETE /sessions/{id}` 归档（课件 §一）。

**场景二：一次性无状态调用**——不想管 Session 生命周期，直接 `POST /agents/{name}/invoke`：临时建一次性 Session、跑完 ReAct 返回（课件 §一/§四）。

**场景三：管理台只读浏览**——运营方打开 `http://localhost:8080/admin`：五个页面（会话列表/Profile 列表/Tool 列表/长期记忆/运行状态）分别调五个 GET 端点渲染；**没有任何新建/编辑/删除按钮**——「能管」要等 30 节（课件 §五）。

**场景四（本节验收场景）：错误统一可预期**——发消息时消息超 32KB → 400；Session 不存在 → 404；Provider 故障 → 503；Agent 调用超 60 秒 → 504；一切其他异常 → 500 且**响应体不含内部异常细节**（连接串、堆栈一个字不漏给外部——门面的分寸，课件 §四最值钱回归）。

**场景五：serve 只靠一个 key 就起得来**——干净机器上只配 `DEEPSEEK_API_KEY` 一个环境变量，`axion serve` 正常起在 8080：不因 Spring AI eager 装配索要 `spring.ai.openai.api-key` 而启动失败（课件 §二坑、autoconfigure.exclude 解法）。

## 功能需求

> 从课件第 26 节与技术方案 §7 提炼：交付物列是本节对外概念的白名单，清单之外的新增对外概念必须停下报告。

| 编号 | 需求 | 交付物（落位模块） | 来源 |
|------|------|-------------------|------|
| FR-1 | **六个 Controller + 10 端点**（统一前缀 `/api/v1`）：`SessionApiController`（POST /sessions 创建、POST /sessions/{id}/messages 发消息、GET /sessions/{id} 查历史、DELETE /sessions/{id} 归档）、`AgentApiController`（POST /agents/{name}/invoke 无状态调用——临时建一次性 Session 跑完返回；**⑨ 实现级明确**：invoke 的一次性 Session 三元组固定 `("web", "invoke", agentName)`、跑完不缓存——审计表 session_id 可追溯且不污染长会话）、`ProfileApiController`（GET /profiles 列 ProfileRegistry）、`MemoryApiController`（GET /memory 返回 MEMORY.md 内容——**注入 core 的 `LongTermMemoryStore.load()`**，实现级明确：不扩 MemoryService 门面，门面只管 ReAct 上下文三件事，运维查询直连 store）、`ToolApiController`（GET /tools 列 ToolRegistry）、`SystemApiController`（GET /health 回 ok、GET /info 含各 Provider 连通状态）。**Controller 必须薄**：参数校验、响应包装、错误处理三件事之外一概不碰；发消息走 `agentService.process`——与 CLI 完全同一入口（宪法 VIII，课件 §二） | 六 Controller（axion-web，com.axion.web.api） | 课件 §一/§二/§四；技术方案 §7.2；CLAUDE.md 端点表 |
| FR-2 | **异常单出口（双信封，拍板 B）**：`GlobalExceptionHandler`（扩展地基既有类，保留其 ErrorResponse 信封形态）——404（`SessionNotFoundException`/`ResourceNotFoundException`）、503（**仅** `ProviderUnavailableException` + 地基 `ServiceUnavailableException`——⑨a 收紧：IllegalStateException 归 500 兜底，课件骨架全域映射与 001/006/008 业务性 IllegalStateException 冲突）、504（`AgentTimeoutException`，Agent 调用 60s 上限）、400（`InvalidRequestException`，消息空/超 32KB）、500 兜底**统一话术「内部错误」不泄漏异常 message**（内部细节进日志）；保留既有 `IllegalArgumentException`→400、`NoResourceFoundException`→404 映射；**`ErrorCode` 枚举补 `GATEWAY_TIMEOUT(504)`**（地基 4 值扩 5 值）；**信封契约**：成功端点响应 = `ApiResponse.ok(data)`、错误响应 = `ErrorResponse.of(errorCode, message)` 且只经 GlobalExceptionHandler 产出（Controller 不得自行 try-catch 拼错误体） | `GlobalExceptionHandler` 扩展 + `ErrorCode` 补值 + 新增 5 异常类（axion-web/api）；地基类零删除 | 课件 §二/§三；技术方案 §7.1/§7.4；拍板 B；⑨a |
| FR-3 | **`serve` 命令落地**（003 交付物改造点）：`ServeCommand` 从占位改为真启动——`web(WebApplicationType.SERVLET)` + `headless(true)`；`application.yaml` 增 `server.port: 8080`（`spring.threads.virtual.enabled: true` 已就位）与 **`spring.autoconfigure.exclude` 排除 `OpenAiAutoConfiguration` + `DashScopeAutoConfiguration`**（课件坑：eager 装配急切实例化索要 `spring.ai.openai.api-key` 卡死 serve；16 节 DashScope 同招、宪法 II 禁用 Spring AI eager 自动装配；排除类全限定名以 mvn dependency:tree 锁定的 spring-ai 版本为准——H3 核实）；**超时实现（实现级明确）**：60s 上限用 `FutureTask.get(60, TimeUnit.SECONDS)` + virtual thread 承载（JDK 原生、无自建线程池——宪法 VII 不变量 ⑤；超时抛 `AgentTimeoutException`）；**⑨c 超时后任务体语义（明示）**：超时后 ReAct 任务体继续跑完、不可强制中断——审计照常落账、历史继续追加、费用继续产生；客户端收到 504 后重试会叠加第二次完整 ReAct——幂等/限流归扩展阶段 | `ServeCommand` 改造（axion-cli）；`application.yaml`（axion-boot） | 课件 §二坑/§三；18 节 serve 埋点；⑨c |
| FR-4 | **防呆限制**：单条消息最大 **32KB**（超 → 400）；Session 历史返回最多最近 **100 条**——防呆不是治理（治理级限流归扩展） | 两常量（Controller 内） | 课件 §二 |
| FR-5 | **springdoc-openapi**：自动生成 OpenAPI 3.0 文档，暴露 `/swagger-ui`——不手写接口文档（web 模块 pom 新增 `springdoc-openapi-starter-webmvc-ui` 依赖） | pom 依赖 + 零代码 | 课件 §四；技术方案 §7.1 |
| FR-6 | **管理平台 v1（只读）**：`axion-web/src/main/frontend/` 下 Vue 3 + Vite 单页应用（与官网同栈同风格）——左侧导航五页（会话/Profile/Tool/长期记忆/运行状态）调五个 GET 端点渲染；**无任何写按钮**；错误时展示错误信封的 message；**构建拍板 B**：`frontend-maven-plugin` 把 `npm ci && npm run build` 绑进 `mvn package`，vite `base: '/admin/'` 产出到 `axion-web/src/main/resources/static/admin/`，Spring 托管在 `/admin`；**`-Dskip.npm` 跳过参数**（落地细节二：后端迭代跑 `mvn -Dskip.npm package` 不背前端构建）；**node 版本锁定（⑨ P2 落地）**：插件 node 版本锁进 pom 并与 `.nvmrc` 同步，企业内网离线构建注记；**SPA 回落**：`/admin/**` 未命中路径回落 `admin/index.html`（`GET /api/v1/**` 不受影响），否则刷新子路由 404 | 前端工程（axion-web/src/main/frontend/）+ pom frontend-maven-plugin（含 skip/node 版本配置）+ SPA 回落配置 | 课件 §五；拍板 B；⑨ |
| FR-7 | **风格 skill `.claude/skills/axion-admin-ui/SKILL.md`**（课件点名交付物）：固化首页设计 token（深色 #000000/#111111/#1a1a1a、分隔 #222222；主色橙 #f97316/hover #ea6a00/#c2550a 仅强调不铺面；文字 #f5f5f5/#a3a3a3/#666666；字体 Inter + JetBrains Mono）+ 工程约定（base '/admin/'、产物落 static/admin、SPA 回落、只调 /api/v1）+ 三态规范（空数据/加载中/错误占位）+ 响应式（窄屏导航收起）+ 验收清单——30 节 Agent 管理页复用同一套；**内置双信封统一请求封装（⑨ P2 落地）**：fetch 成功解析 `ApiResponse.data`、错误解析 `ErrorResponse.errorCode/message` 的公共工具函数——页面不手写两套解析，前端侧的信封边界防线；**不引外部 skill**（课件已核过 mattpocock/skills 是工程师流程技能、与本项目审美无关） | `.claude/skills/axion-admin-ui/SKILL.md` | 课件 §五；⑨ |
| NFR-1 | 全程同步阻塞：请求在 virtual thread 上直进直出（`spring.threads.virtual.enabled`），LLM 等待自动让出——不引入 WebFlux/响应式代码（宪法 VII，课件 §三） | — | 宪法 VII；课件 §三 |
| NFR-2 | 核心阶段不做清单：认证（假设内网）、SSE 流式、WebSocket、限流、RBAC——全部不做（课件 §二；扩展阶段补） | — | 课件 §二；技术方案 §7.3 |
| NFR-3 | **门面分寸**：500 兜底响应不含内部异常 message（连接串/堆栈不泄漏），内部细节只进日志——最值钱回归钉死 | — | 课件 §三/§四 |

### 核心代码骨架（课件 26 节骨架一致，形态机械适配：双信封拍板 B——成功 ApiResponse / 错误 ErrorResponse）

```java
// axion-web：com.axion.web.api —— 最典型的 Controller：承载 ReAct 循环的端点（薄：三件事之外不碰业务）
@RestController
@RequestMapping("/api/v1/sessions")
public class SessionApiController {

  private final AgentService agentService;
  private final SessionManager sessionManager;

  @PostMapping("/{id}/messages")
  public ApiResponse<MessageResponse> send(@PathVariable String id, @RequestBody MessageRequest req) {
    if (req.content() == null || req.content().isBlank() || req.content().length() > 32 * 1024) {
      throw new InvalidRequestException("消息为空或超过 32KB");          // → 400
    }
    Session session = sessionManager.findById(id)
        .orElseThrow(() -> new SessionNotFoundException(id));          // → 404
    String reply = agentService.process(session, req.content());        // 与 CLI 完全同一个入口（宪法 VIII）
    return ApiResponse.ok(new MessageResponse(reply));
  }
}
```

```java
// axion-web：com.axion.web.api —— 统一异常出口（拍板 B：保留地基 ErrorResponse 双信封形态，本节扩展映射）
@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  // 保留地基既有映射：MethodArgumentNotValidException→400、NoResourceFoundException→404 等不动

  @ExceptionHandler({SessionNotFoundException.class, ResourceNotFoundException.class})
  public ResponseEntity<ErrorResponse> notFound(RuntimeException e) {
    return ResponseEntity.status(404).body(ErrorResponse.of(ErrorCode.NOT_FOUND, e.getMessage()));
  }

  @ExceptionHandler({ProviderUnavailableException.class, ServiceUnavailableException.class})
  public ResponseEntity<ErrorResponse> providerDown(RuntimeException e) {
    // ⑨a：503 只映射「依赖不可用」语义类——IllegalStateException 归 500 兜底（课件骨架全域映射会语义污染）
    return ResponseEntity.status(503).body(ErrorResponse.of(ErrorCode.SERVICE_UNAVAILABLE, e.getMessage()));
  }

  @ExceptionHandler(AgentTimeoutException.class)
  public ResponseEntity<ErrorResponse> timeout(AgentTimeoutException e) {
    return ResponseEntity.status(504).body(ErrorResponse.of(ErrorCode.GATEWAY_TIMEOUT, e.getMessage())); // 枚举补值
  }

  @ExceptionHandler(InvalidRequestException.class)
  public ResponseEntity<ErrorResponse> badRequest(InvalidRequestException e) {
    return ResponseEntity.status(400).body(ErrorResponse.of(ErrorCode.BAD_REQUEST, e.getMessage()));
  }

  @ExceptionHandler(Exception.class)   // 兜底——门面分寸：不把 e.getMessage() 吐给外部
  public ResponseEntity<ErrorResponse> internal(Exception e) {
    LOG.error("Unhandled exception", e);                              // 细节只进日志
    return ResponseEntity.status(500).body(ErrorResponse.of(ErrorCode.INTERNAL_ERROR, "内部错误"));
  }
}
```

```yaml
# application.yaml 增补（axion-boot）——serve 只认 DEEPSEEK_API_KEY 一个 key（课件坑）
spring:
  autoconfigure:
    exclude:
      - org.springframework.ai.autoconfigure.openai.OpenAiAutoConfiguration
      - com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeAutoConfiguration
server:
  port: 8080
```

### 本节交付物清单（Spec-Kit 拆解锚点 / axion-spec 交付清单比对基准）

- **代码**：六 Controller（Session/Agent/Profile/Memory/Tool/System）；新增 5 异常类（InvalidRequestException/SessionNotFoundException/ResourceNotFoundException/ProviderUnavailableException/AgentTimeoutException）；`GlobalExceptionHandler` 扩展（保留 ErrorResponse 信封 + 504 + 500 不泄漏）；`ErrorCode` 补 `GATEWAY_TIMEOUT(504)`；`ServeCommand` 改造（web(SERVLET) 真启动）
- **测试**：`SessionApiControllerTest`（@WebMvcTest 切片：32KB→400 / Session 不存在→404 / process 恰调一次——Controller 没夹带私货）、`GlobalExceptionHandlerTest`（每类异常映射到约定状态码 / **信封边界：错误响应均为 ErrorResponse、成功响应均为 ApiResponse** / **500 不含内部异常 message——最值钱回归**）、`WebSmokeIT`（@SpringBootTest 真上下文：/health /info /profiles /tools 真实可达——JPA repository 扫描红线，18 节「Found 0 repositories」坑复发第一时间红）
- **表**：无新增（会话归档复用 002 的 sessions 表）
- **配置**：`spring.threads.virtual.enabled`（已就位）+ `server.port: 8080` + `autoconfigure.exclude` 两排除 + 32KB/100 条常量 + frontend-maven-plugin 的 `-Dskip.npm` 跳过参数
- **改造点**：`ServeCommand`（003 交付物）占位改真启动；`GlobalExceptionHandler`（地基类）扩展 + `ErrorCode` 补值——**地基类零删除**（拍板 B）
- **前端**：`axion-web/src/main/frontend/` Vue3+Vite 只读管理台（五页）+ `frontend-maven-plugin` 构建串联（拍板 B，含 skip 参数）+ 产物 `static/admin/` + SPA 回落 `/admin/**` → index.html
- **skill**：`.claude/skills/axion-admin-ui/SKILL.md`（设计 token/工程约定/三态/验收清单——30 节复用）

![Web Service 全链路：业务系统 REST 接入 → 六个薄 Controller（校验/包装/错误三件事，逻辑全委托核心层）→ AgentService.process 与 CLI 完全同一入口（宪法 VIII）→ 10 端点统一前缀 /api/v1；异常单出口 GlobalExceptionHandler 统一 ErrorResponse 信封（400/404/503/504/500 兜底不泄漏内幕——最值钱回归，拍板 B 双信封：成功 ApiResponse/错误 ErrorResponse）；serve 8080 virtual thread + 排除 Spring AI eager 装配（单 key 可启动）；/admin 只读管理台五页调五 GET（Vue3+Vite 同官网风格 + axion-admin-ui skill，frontend-maven-plugin 构建含 skip 开关，SPA 回落）；认证/SSE/WebSocket/限流/RBAC 与 Agent 管理端点归扩展](../../website/public/images/docs-web-service-flow.svg)

## 明确不做

> 来源：课件 §二「别手痒」/§五「有几样先别做」、技术方案 §7.3、宪法 VII。

- **Agent 目录增删改端点（/api/v1/agents 增删改）**：29/30 节正题（含一句话生成 AGENT.md）——核心阶段「定义一个 Agent」= 手写目录 + 重启/热加载（ContextLoader 每次组装 prompt 重新读取）；与调度运行时接口一起扩展阶段补齐，不拆开先做一半（技术方案 §7.3 原文）
- **Memory 写入/清空/搜索端点**：核心阶段 Memory 只有读（GET /memory）；写由 Agent 的 save_memory Tool 承担
- **Webhook 触发、SSE 流式响应、WebSocket**：扩展阶段
- **认证（假设内网）、限流、RBAC**：扩展阶段补 API Key + JWT 等；32KB/100 条只是防呆不是治理
- **Prometheus metrics**：扩展阶段（现状 Actuator 已有 health/info 暴露，不做自建指标）
- **管理平台的写操作**：第一版只能"看"（列表/详情/状态）——界面上不出现假按钮（课件 §五）
- **Tool describe 与调用历史、LLM call 历史/token 统计端点**：扩展阶段（技术方案 §7.3）

## 验收标准

### 自动化部分（harness 承载，`mvn clean verify` 全绿即通过）

Web 层 harness 用 `@WebMvcTest` 切片——只起 MVC 层、mock 掉 AgentService，不碰模型不碰库，跑得飞快（课件 §四）：

| 测试类 | 覆盖的验收点 |
|--------|-------------|
| `SessionApiControllerTest`（@WebMvcTest） | 超 32KB → 400；Session 不存在 → 404；正常请求 `agentService.process` 恰被调一次（Controller 没夹带私货——薄 Controller 的机器证据） |
| `GlobalExceptionHandlerTest` | 每类异常映射到约定状态码（400/404/503/504/500）；**信封边界（拍板 B 漂移防线）**：错误响应均为 `ErrorResponse` 信封（errorCode/message/timestamp）、成功响应均为 `ApiResponse` 信封（code/message/data/timestamp）——新端点选错信封测试即红；**⑨a 回归：IllegalStateException → 500 非 503**（业务校验异常不被报成「Provider 故障」）；**500 时响应里不含内部异常的 message（不泄漏）** |
| `SessionManager` 回归（⑨b） | **重启后 findById 恢复历史**（库里命中则恢复）；**发消息后 findById 可见最新**（缓存/库一致性钉死——Web 场景第一次考验 002 的持久化时机） |
| `WebSmokeIT`（@SpringBootTest 真上下文，不依赖模型） | `/health`、`/info`、`/profiles`、`/tools` 真实链路可达——验证 Bean 装配和扫描范围没炸；**JPA repository 扫描红线**：18 节「Found 0 repositories」坑在 web 模块复发时第一时间红（不用等到手动 serve） |

最值钱的一个——「门面的分寸」回归（课件 §四原文，实现之前写最划算）：

```java
@Test
void 内部异常细节_绝不能出现在500响应里() {
    when(agentService.process(any(), any()))
        .thenThrow(new IllegalStateException("jdbc:sqlite:/data/axion.db connect failed"));
    mockMvc.perform(post("/api/v1/sessions/s-1/messages").contentType(JSON).content(body))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.errorCode").value(500))                          // 拍板 B：错误走 ErrorResponse 信封
        .andExpect(jsonPath("$.message").value("内部错误"))                      // 统一话术
        .andExpect(content().string(not(containsString("jdbc:sqlite"))));       // 连接串这类内幕一个字不漏
}
```

跑法：`mvn test` 日常全跑；全量 `mvn clean verify` 收尾——**全量全绿，不写死用例数**（007 ⑦e 口径延续）。

### 人工部分（做完怎么验）

```bash
axion serve                                          # 启动，默认 8080（只配 DEEPSEEK_API_KEY 一个 key 就起得来）
curl -X POST localhost:8080/api/v1/sessions           # 建会话
curl -X POST localhost:8080/api/v1/sessions/{id}/messages \
     -H 'Content-Type: application/json' -d '{"content":"今天北京天气怎么样"}'   # 完整 ReAct 返回答复
curl localhost:8080/api/v1/tools                      # 列工具
open http://localhost:8080/admin                      # 管理平台（五页只读）
open http://localhost:8080/swagger-ui                 # 接口文档（自动生成）
```

- **端到端验收（Demo 四/五 地基）**：Web 同步调用跑通（真模型：POST messages 拿到完整 ReAct 答复）+ 多端点联动（先 GET /profiles 拿 Agent 名 → POST /agents/{name}/invoke 无状态调用）——需求文档 §13 的 Web Service 验收点
- **干净机器单 key 启动**：环境只配 `DEEPSEEK_API_KEY`，`axion serve` 正常起在 8080（课件坑的实机验证——autoconfigure.exclude 是否真挡住 eager 装配）
- **管理台只读核对**：/admin 五页渲染正常、无任何写按钮、错误时展示统一信封 message

## 依赖与假设

### 前序交付物（已就位，本节直接依赖）

- **002-react**：`AgentService.process(Session, String)`、`SessionManager`（findById 语义——**现状为 getOrCreate 三元组，findById 需核实**：若缺失则 SessionManager 改造点补 `findById(String)`——实施时 H3 核对，缺失即停）、`Session` 模型
- **003-cli**：`ServeCommand` 占位（改造点）、`CliAgentConfiguration` 装配、重命令启动先例
- **005-tool**：`ToolRegistry`（GET /tools 数据源）
- **006-memory**：`LongTermMemoryStore.load()`（GET /memory 数据源——实现级明确直连 store）
- **007-sandbox**：`ApiResponse` 信封现状（地基产物）+ WhitelistSandbox（不影响本节）
- **008-scheduler**：`ProfileRegistry.list()`（GET /profiles 数据源）+ `AgentScheduler`（serve 常驻时钟推并行运转——serve/gateway 常驻模式下定时任务随进程跑，CLAUDE.md「定时任务随 serve/gateway 常驻」契约兑现）

**现状确认（2026-09-08 实测）**：axion-web 有地基 5 件（ApiResponse/ErrorResponse/ErrorCode/GlobalExceptionHandler/ServiceUnavailableException，java-spring-init 产物）；`ServeCommand` 占位（web(NONE) + 占位输出）；`spring.threads.virtual.enabled: true` 已在 application.yaml；`server.port`/`autoconfigure.exclude` 无；`SessionManager` 公开方法为 `getOrCreate`（**无 findById**——实施时补 `findById` 或按 session_id 直查 Repository，改造点）；web 模块 pom 的 web starter/springdoc/test 依赖待核实补缺。

### 前序缺口（H0 依赖检查）

- **`SessionManager.findById` 缺失**：课件骨架 `sessionManager.get(id).orElseThrow(...)` 需要按 id 查 Session；现状只有 `getOrCreate(channel, userId, profileName)`。方案二选一（实施时经用户确认）：a. `SessionManager` 补 `Optional<Session> findById(String)`（前序公共接口新增方法——改造点）；b. Controller 直查 `SessionRepository`（绕 SessionManager——不推荐，违反会话内存态契约）。**默认 a，列入改造点**。**⑨b 一致性前置**：实施前 H3 核实 002 的持久化时机（缓存内实例与库的同步语义），补回归「重启后恢复历史」「发消息后最新可见」——缓存/库不一致则先停报告。
- **⑨d Session 并发面（跨节契约）**：本节是三触发源第一次在常驻进程同场——Web 并发请求 + 008 钟推可能同时 `process` 同一 Session（scheduler 三元组会话长期复用）；002 的 `Session.append`/ReActLoop 并发语义未经考验。实施前核实线程安全性，无保护则本节补 per-session 最小互斥（008 per-task 锁同款）；不得默认「应该没事」。

### 改造点（经拍板允许修改的前序公共接口）

- **`GlobalExceptionHandler` 扩展**（地基类：保留 ErrorResponse 信封 + 新增 404/503/504/400 映射 + 500 不泄漏 + 保留既有映射——**地基类零删除**，拍板 B）
- **`ErrorCode` 补 `GATEWAY_TIMEOUT(504)`**（地基枚举 4 值扩 5 值）
- **`ServeCommand`**（003 交付物：占位改真启动 web(SERVLET)）
- **`SessionManager` 补 `findById(String)`**（002 交付物：课件骨架所需，实施时 H3 核实后落地）
- 其余前序公共接口零改动：`AgentService`/`ToolRegistry`/`LongTermMemoryStore`/`ProfileRegistry` 原样

### 外部依赖与假设

- **新第三方依赖**（课件点名）：`springdoc-openapi-starter-webmvc-ui`（OpenAPI 文档）+ `frontend-maven-plugin`（构建拍板 B——版本以实施时 H3 核实的兼容线为准）；前端栈 Vue 3 + Vite（与 website/ 同栈）
- **课件口径（用户拍板 2026-09-08）**：整体方案参考 26 节课件；信封 A、构建 B 已拍板
- **线程模型**：请求在 virtual thread 直进直出（宪法 VII）；60s 超时用 `FutureTask.get(60s)` + 虚线程承载任务体（JDK 原生、无自建线程池——不变量 ⑤）；不引入 WebFlux
- **排除类全限定名 H3 核实**：`OpenAiAutoConfiguration`/`DashScopeAutoConfiguration` 的包名以 mvn dependency:tree 锁定的 spring-ai 版本为准（课件原话）
- **跨节契约**：10 端点 + 统一信封是 28 节管理台扩展（调度管理端点）、30 节 Agent 管理页、31 节 Demo 四/五 的消费契约；信封格式（code/message/data/timestamp）此后不改；29/30 节的 Agent 增删改端点届时挂同一前缀与信封
- **跑通标准**：本节自身验收 = harness 全绿 + serve 实跑（curl 四命令 + /admin + /swagger-ui）；Demo 四/五（Web 同步调用 + 多端点联动）在 31 节合并验收
