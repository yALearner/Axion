# Research: 009-web-service（技术选型与裁决记录）

> 本 feature 无未决 NEEDS CLARIFICATION——全部裁决已在需求文档钉死（2026-09-08：修订说明 ①~⑥，含课件口径拍板、信封 B/构建 B 拍板、四维修正 ⑨ 落位）。本文件记录裁决内容与备选，作为 plan/tasks 的依据。

## 裁决 1：信封形态——双信封（拍板 B，2026-09-08）

- **Decision**: 保留地基双信封——成功 `ApiResponse`（code/message/data/timestamp）、错误 `ErrorResponse`（errorCode/message/timestamp）且只经 GlobalExceptionHandler 单出口产出；地基类零删除
- **Rationale**: 用户拍板（2026-09-08，四维分析后推翻初选 A）——双信封零改造；契约漂移风险由 `GlobalExceptionHandlerTest` 信封边界断言（错误均 ErrorResponse、成功均 ApiResponse）机器钉死
- **Alternatives considered**: 单一 ApiResponse 信封（技术方案 §7.1/课件口径——否决：用户拍板按地基双信封）

## 裁决 2：前端构建串联——frontend-maven-plugin（拍板 B，2026-09-08）

- **Decision**: `frontend-maven-plugin` 把 `npm ci && npm run build` 绑进 `mvn package`；配 `-Dskip.npm` 跳过参数（后端迭代不背前端构建）；node 版本锁进 pom 与 .nvmrc 同步；内网离线构建注记
- **Rationale**: 用户拍板；构建产物一致性由工具强制而非纪律（改前端忘 build → 打包旧前端的风险消除）；skip 参数化解唯一痛点
- **Alternatives considered**: 手动 npm build（否决：产物漂移风险无解，靠人记流程）

## 裁决 3：503 映射收紧（⑨a，P1 修正）

- **Decision**: 503 只映射 `ProviderUnavailableException` + 地基 `ServiceUnavailableException`；`IllegalStateException` 归 500 兜底
- **Rationale**: 课件骨架 `{IllegalStateException, ProviderUnavailableException}` 全域映射与项目现状冲突——001 ConfigLoader 校验/006 mem0 缺凭证/008 非法 zone 均抛 IllegalStateException 作业务校验；全域 503 = 语义污染（配置错误被报成 Provider 故障，业务系统按 503 重试/降级方向全错）
- **Alternatives considered**: 课件骨架原样（否决：语义污染）；IllegalStateException 单独映射 500（与兜底重复，无必要）

## 裁决 4：60s 超时实现（⑨c 实现级明确）

- **Decision**: `FutureTask.get(60, TimeUnit.SECONDS)` + virtual thread 承载任务体；超时抛 `AgentTimeoutException`→504；**超时后任务体继续跑完、不可强制中断**——审计照常落账、历史继续追加、费用继续产生；客户端重试叠加第二次 ReAct——幂等/限流归扩展
- **Rationale**: JDK 原生、无自建线程池（宪法 VII 不变量 ⑤）；LLM 调用不可杀是事实边界，文档明示不伪装
- **Alternatives considered**: 异步模型/CompletableFuture（否决：宪法 VII）；忽略超时（否决：课件点名 504 口径）

## 裁决 5：GET /memory 直连 store（实现级明确）

- **Decision**: `MemoryApiController` 注入 core 的 `LongTermMemoryStore` 调 `load()`；不扩 `MemoryService` 门面
- **Rationale**: 门面只管 ReAct 上下文三件事（buildContext/remember/recall）；运维查询语义 = MEMORY.md 原文 = store.load()；未来门面扩展时再收口
- **Alternatives considered**: 扩 MemoryService 门面加 loadLongTermMemory（否决：改前序公共接口换不来语义增益）；buildContext 复用（否决：混入会话历史语义不符）

## 裁决 6：invoke 一次性 Session 三元组（⑨ P2 钉死）

- **Decision**: `("web", "invoke", agentName)`、跑完不缓存
- **Rationale**: 审计表 session_id 可追溯且不污染长会话；「把 Agent 当函数用」的无状态语义
- **Alternatives considered**: 不落库裸跑（否决：审计断链）；复用固定会话（否决：无状态调用语义破坏）

## 裁决 7：排除 eager 装配（课件坑，H3 核实）

- **Decision**: application.yaml `spring.autoconfigure.exclude` 排除 `OpenAiAutoConfiguration` + `DashScopeAutoConfiguration`（全限定名以 mvn dependency:tree 锁定的 spring-ai 版本为准）
- **Rationale**: deepseek 走 spring-ai-openai starter 的 OpenAiAutoConfiguration 急切实例化索要 spring.ai.openai.api-key，Axion 不用该自动 Bean（Provider 由 ProviderChatModelFactory 显式构造，宪法 III）——不排除则 serve 逼运营方配两个 key、卡死 31 节干净部署
- **Alternatives considered**: 配 dummy spring.ai.openai.api-key（否决：掩盖问题、多一个 key 心智）

## 裁决 8：前序改造点四处（经拍板）

- **Decision**: GlobalExceptionHandler 扩展（保留 ErrorResponse + 新增映射 + 500 不泄漏）、ErrorCode 补 GATEWAY_TIMEOUT(504)、ServeCommand web(SERVLET) 真启动、SessionManager 补 findById（默认方案 a）
- **Rationale**: 课件骨架所需 + 地基零删除（拍板 B）；⑨b 一致性前置（findById 的缓存/库同步 H3 核实 + 回归）；⑨d 并发面核实（三触发源同场，无保护则补 per-session 互斥）
- **Alternatives considered**: Controller 直查 SessionRepository（否决：绕 SessionManager 违反会话内存态契约）
