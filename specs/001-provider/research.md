# Phase 0 Research: 001-provider

## R1: Spring AI 1.1.8 的 ChatModel 调用与"关闭自动执行"的确切形态

> **T001 核实结论（2026-08-30，mvn dependency:tree + jar 反查实测）**：
> ① `spring-ai-starter-model-openai:1.1.8` 在锁定 BOM 中解析成功（OpenAI 兼容端点，DeepSeek/Kimi 走 `base-url` 配置接入）
> ② 调用 API：`ChatModel.call(Prompt)` 同步返回 `ChatResponse`（`getResult().getOutput()` → AssistantMessage；`hasToolCalls()` 判断工具请求）
> ③ **关闭自动执行的确切写法**：`ToolCallingChatOptions.setInternalToolExecutionEnabled(false)`（1.1.8 中该开关存在于 `org.springframework.ai.model.tool.ToolCallingChatOptions`，回归测试以 ArgumentCaptor 断言此值为 false）
> ④ 工具翻译形态：适配器产出 `ToolDefinition`（`name()`/`description()`/`inputSchema()` String），经 `ToolCallbacks` 挂上请求；`ToolCallingManager.executeToolCalls` 是自动执行入口——**本模块不调用它**

- **Decision**: 实施第一步先跑 `mvn dependency:tree`（axion-provider 模块）核实：① `ChatModel.call(...)` 在锁定版本中的签名与返回值结构；② 请求构造中关闭自动 tool 执行的确切写法（选项/标志名随版本变）；③ 目标 Provider（DeepSeek/Kimi）的 starter 依赖在 spring-ai-bom 1.1.8 中是否存在、能解析下载。核实结果写入任务日志后再动手写代码。
- **Rationale**: 技术方案 §1.1 决策四明确要求"研发前对当前版本核实"；课件第 16 节坑三记录过真实事故——某些 milestone BOM 未必包含每一家的独立 starter。
- **Alternatives considered**: 照教程示例直接写 → 被否决（违反写前 H3 门禁，可能引入不存在的 API 或依赖）。

## R2: llm_calls 手工建表方案

- **Decision**: `schema.sql` 手工建表脚本放 `axion-storage/src/main/resources/`，含 `success`/`error_message` 两列（对需求文档 §10 的已批准补充修订）；测试 `LlmCallRepositoryTest` 显式执行同一份 `schema.sql` 建表，不让 Hibernate 自动建。
- **Rationale**: SQLite 的 ALTER TABLE 支持弱，`hibernate.ddl-auto=update` 不可依赖（技术方案 §9.2 工程风险提示）；测试与生产走同一份脚本，防止"测试绿了、生产列名对不上"。
- **Alternatives considered**: 依赖 ddl-auto 自动建表 → 被否决（宪法技术约束）；引入 Flyway → 核心阶段不引入额外组件，后续表结构演进时再评估。

## R3: Function Calling 翻译方案（AxionTool → Spring AI 工具格式）

- **Decision**: 适配器读取 `AxionTool.getInputSchema()`（JsonSchema 形态），映射为 Spring AI 锁定版本的工具描述对象；翻译产物只含 schema 说明，不含执行逻辑；模型返回的 toolCall 请求原样透传。
- **Rationale**: Spring AI 已做各家协议转换，Axion 只复用格式转换（宪法 II）；"只翻译不执行"是本节最容易埋 bug 的边界，有回归测试钉死。
- **Alternatives considered**: 自行实现各家协议格式 → 被否决（重复造轮子，违背复用决策）。

## R4: 审计字段补充（success / error_message）

- **Decision**: `llm_calls` 增加 `success`（BOOLEAN）与 `error_message`（TEXT，可空）两列；调用失败时审计先落账（success=false + 原因）再把异常上抛。
- **Rationale**: 需求文档 §10 原表结构无这两列，失败调用将无痕迹，与 `tool_invocations` 不对称；需求文档 001-provider 已批准此补充修订。
- **Alternatives considered**: 沿用 §10 原结构、失败只记日志 → 被否决（宪法 V：审计不能靠日志兜底）。

## R5: 测试栈与集成冒烟机制确认

- **Decision**: 单测用 JUnit 5 + Mockito（工程地基测试栈，以模块 pom 锁定为准）；集成冒烟 `ProviderSmokeIT` 用 `@Tag("integration")` 标记，surefire 默认排除、本地 `-Dgroups=integration` 手动跑（跑法以地基 CI 配置实际开关为准，实施时核对）。
- **Rationale**: 外部 API 可用性不得成为自己流水线的可用性；单测秒级、冒烟手动。
- **Alternatives considered**: 冒烟混入默认测试套件 → 被否决（CI 依赖外网不稳定）。
