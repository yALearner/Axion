# Research: ReAct Runtime（第一周）

Phase 0 研究输出。格式：Decision / Rationale / Alternatives considered。全部版本与类名均经 Maven Central、官方源码 jar 或官方文档验证。

## R1. Spring AI 版本路线（Provider 协议转换 + Tool schema 生成）

- **Decision**: 升级到稳定 GA 线：Spring Boot 3.3.5 → **3.5.16**（3.5.x 最新 patch，Maven Central 已验证），Spring AI 1.0.0-M4 → **GA 1.1.8**（1.1.x 最新 patch；官方文档确认 1.0.x/1.1.x 均支持 Boot 3.4.x/3.5.x，且 v1.1.8 根 POM 本身构建于 Boot 3.5.15 之上；2.0.x 需 Boot 4.x 不可用）。使用 Spring AI 官方 OpenAI starter 对接 DeepSeek/Kimi；**移除 spring-ai-alibaba**（其 M3.2 无 OpenAI 兼容 starter，仅对接 DashScope，第一周用不上；移除后 M3/M4 混跑冲突与 milestone 仓库需求一并消失）。
- **Rationale**:
  - 宪法原则二字面要求"`@Tool` 注解的 JSON Schema 生成"，但 `@Tool` 在 Spring AI **1.0.0-M7** 才引入（M4 的 `spring-ai-core` 无 `org/springframework/ai/tool` 包，已从 M4 sources jar 验证）；M8 定名 `ToolCallback`。停在 M4 属"精神符合、字面偏差"，且后续迁移要改造 ReAct 循环。
  - 9 模块骨架几乎为空（仅 package-info），现在升级成本最低，且 GA 稳定版是企业底座应有的依赖姿态。
  - DeepSeek/Kimi 均为 OpenAI 兼容 API，Spring AI 官方 OpenAI starter 一条通道覆盖两家（M4 已验证 `spring.ai.openai.base-url` 覆盖可用）。
  - Alibaba M3.2 编译期依赖 `spring-ai-core:1.0.0-M3`，与 M4 BOM 存在跨里程碑混跑风险（UNVERIFIED 二进制兼容）；移除后该冲突彻底消失。未来需要 Qwen/DashScope 时按 GA 版本线重新引入。
- **Alternatives considered**:
  - 保持 M4 + `FunctionCallback.method()` 生成 schema（零 pom 变更）——被拒：宪法字面偏差 + 旧里程碑 API，迁移成本后置。
  - 升 M8 保留 Boot 3.3.5 ——被拒：仍是非稳定里程碑，且 M8 官方适配 Boot 3.4.x，与 3.3.5 组合无背书。
  - Spring AI 1.0.9（1.0.x 最新 patch）——API 与 1.1.8 完全一致，作为保守回退；新项目直接上 1.1.x。

## R1-GA. Spring AI GA 精确 API（v1.0.9 / v1.1.8 源码逐类验证）

- **Tool 定义**：`@Tool` 注解 = `org.springframework.ai.tool.annotation.Tool`（属性 `name`/`description`/`returnDirect`/`resultConverter`）；从 Bean 生成回调：`ToolCallbacks.from(Object...)`（`org.springframework.ai.support`）或 `MethodToolCallbackProvider.builder().toolObjects(bean).build().getToolCallbacks()`（`org.springframework.ai.tool.method`）。`FunctionCallback` 在 GA 已**移除**，现行为 `ToolCallback`（`org.springframework.ai.tool`）。
- **禁用内部执行**（宪法原则二的关键开关）：`ToolCallingChatOptions.builder().internalToolExecutionEnabled(false)`（`org.springframework.ai.model.tool`；1.x 默认 true）。**1.x 没有 `proxyToolCalls`**（那是 2.0 概念，勿用）。
- **读取 Tool 调用**：`AssistantMessage.getToolCalls()` → `List<AssistantMessage.ToolCall>`，嵌套 record `(String id, String type, String name, String arguments)`，访问器 `id()/type()/name()/arguments()`；`hasToolCalls()` 辅助方法。循环结束判定：无 tool calls 即最终答案。
- **回填结果**：`new ToolResponseMessage(List<ToolResponse>)`，嵌套 record `ToolResponse(String id, String name, String responseData)`（字段名是 `responseData` 不是 `response`）——与 GA 内部 `DefaultToolCallingManager` 的组装方式一致。
- **OpenAI starter**：artifact = `org.springframework.ai:spring-ai-starter-model-openai`；配置键 `spring.ai.openai.base-url` / `spring.ai.openai.api-key` / `spring.ai.openai.chat.options.model`（默认 gpt-4o-mini，必须覆盖）。GA 的 `OpenAiApi` 仍以 `baseUrl + "/v1/chat/completions"` 拼最终 URL：
  - DeepSeek：`base-url=https://api.deepseek.com` → `https://api.deepseek.com/v1/chat/completions` ✅（官方接受两种 base）
  - Kimi：`base-url=https://api.moonshot.cn` → `https://api.moonshot.cn/v1/chat/completions` ✅（**不得**在 base-url 里带 `/v1`，否则变 `/v1/v1/`）
- **Schema 生成**：GA 内部用 victools `jsonschema-generator`（`org.springframework.ai.util.json.schema.JsonSchemaGenerator`），**不要**引入 `jackson-module-jsonSchema`（M4 旧机制，GA 已移除）。`@Tool` 参数元数据可用 `@ToolParam`/`@JsonPropertyDescription` 等标注。
- **仓库**：GA 全部在 Maven Central；**删除**根 POM 的 `spring-milestones` 仓库（Alibaba 里程碑依赖移除后无需求）。
- **注意**：starter 传递引入 `spring-ai-autoconfigure-model-chat-client`（ChatClient 自动配置）——只使用 `ChatModel` 直调，不得使用 ChatClient 的自动 tool 执行（宪法 I/II）。

## R2. SQLite + Spring Data JPA（审计两表）

- **Decision**:
  - 依赖：`org.hibernate.orm:hibernate-community-dialects`（Boot 3.5.16 BOM 管理，版本 6.6.53.Final，**不显式写版本**）；`org.xerial:sqlite-jdbc` 采用 Boot 3.5.16 BOM 管理的 **3.49.1.0**（父 POM 原固定 3.45.3.0 需删除或显式对齐）。
  - Dialect 显式配置：`spring.jpa.properties.hibernate.dialect=org.hibernate.community.dialect.SQLiteDialect`（不依赖自动探测）。
  - 主键：`GenerationType.IDENTITY`（SQLiteDialect 支持，`select last_insert_rowid()`；AUTO 也解析为 IDENTITY，但显式声明）。
  - 建表：`spring.jpa.hibernate.ddl-auto=none` + `spring.sql.init.mode=always` + `src/main/resources/schema.sql`（`CREATE TABLE IF NOT EXISTS` 幂等）。
    - **关键坑**：Spring Boot 的 `EmbeddedDatabaseConnection` 枚举不含 SQLite，`spring.sql.init.mode` 默认 `embedded` 会**静默跳过** schema.sql，必须显式 `always`。
    - 顺序保证：默认 schema.sql 在 EntityManagerFactory 创建**之前**执行（`JpaDependsOnDatabaseInitializationDetector` 加 depends-on）；不需要 `spring.jpa.defer-datasource-initialization`。
  - 数据源：`spring.datasource.url=jdbc:sqlite:.axion/axion.db?foreign_keys=on&journal_mode=wal&busy_timeout=5000`；driver `org.sqlite.JDBC`；Hikari `maximum-pool-size=4`；`spring.jpa.open-in-view=false`。
    - SQLite 外键默认**关闭**且按连接生效——必须走 URL pragma（Hikari `connectionInitSql` 只执行首条语句，多 PRAGMA 放 URL）。
  - 测试：`jdbc:sqlite::memory:` + **`maximum-pool-size=1`** + `ddl-auto=create-drop` + `spring.sql.init.mode=never`；另加一个专门跑生产 `schema.sql` 的校验测试（`mode=always` + `ddl-auto=none` 同一内存库验证 DDL 有效）。
    - **关键坑**：`:memory:` 每个连接是独立空库，池 >1 时 schema 会"消失"；`WAL` 对内存库无效，只有池=1 可靠。
- **Rationale**: 宪法要求 SQLite + 手动建表脚本；上述组合是 Boot 3.5.16 + Hibernate 6.6.53 下验证过的最小可靠方案（外键、WAL、busy_timeout 均来自 sqlite-jdbc 源码的 URL pragma 解析；pool≤4 + WAL 是社区验证的锁规避方案）。
- **Alternatives considered**:
  - Flyway/Liquibase——宪法倾向普通脚本，暂不引入。
  - 临时文件测试库（每测试一个文件）——需要清理纪律；内存库+池1 更简单，多连接需求出现时再切。
  - `hibernate.ddl-auto=update`——宪法明令禁止（SQLite ALTER TABLE 弱）。

## R3. Logback 结构化 JSON 日志

- **Decision**: `net.logstash.logback:logstash-logback-encoder:8.0`（root POM 依赖管理引入）；CLI 模式 = 控制台纯文本 + 滚动 JSON 文件 `logs/axion.jsonl`（常开）；`server` Spring profile 下 JSON 同时上 stdout。`logback-spring.xml` 放 axion-cli / axion-boot 资源目录。
- **Rationale**:
  - Boot 3.3.5 管理 Logback **1.5.11**、Boot 3.5.16 管理 **1.5.34**（非 1.4.x）；encoder 8.0 要求 logback ≥1.5.0 且实测配套 Jackson 2.17/2.18——完全匹配，零冲突。
  - encoder 9.0 需要 Jackson 3，Boot 3.5.x 仍用 Jackson 2.x，不可用。
  - Logback 原生 `ch.qos.logback.classic.encoder.JsonEncoder`（1.3.8+）未标 GA、不支持自定义字段命名、throwable causes 丢失（LOGBACK-1749），不满足"字段名 = provider/model/tokens/duration_ms/session_id"的要求。
  - 结构化字段分工：`MDC.put("session_id", …)`（会话级，try/finally 清理）+ `StructuredArguments.kv("provider", …)` 等（事件级，渲染为顶级 JSON 字段）。
  - Java 21 虚拟线程内 ThreadLocal/MDC 正常（JEP 444）；真正的坑是 MDC 不跨线程继承（Logback 用非 inheritable ThreadLocal），本里程碑 CLI 单线程交互不受影响，Web 阶段需要时用 `ContextPropagatingTaskDecorator` 或手动 copy。
- **Alternatives considered**:
  - Spring Boot 3.4+ 内置 structured logging——需升级 Boot（已随 R1 升到 3.5.x，但该特性仍在演进中且 encoder 8.0 已满足需求），暂不采用。
  - log4j2——整体替换，无收益。

## 对 plan.md 的回填项

1. Technical Context 的 Primary Dependencies / Storage / Testing 三处 NEEDS CLARIFICATION 全部消除。
2. 父 POM 变更清单（实施阶段执行）：
   - `spring-boot-starter-parent` 3.3.5 → 3.5.16
   - `spring-ai.version` 1.0.0-M4 → 1.1.8
   - 移除 `spring-ai-alibaba.version` 属性与 Alibaba 依赖（DeepSeek/Kimi 走官方 OpenAI starter）
   - 删除 `spring-milestones` 仓库（GA 全在 Maven Central）
   - 新增 `logstash-logback-encoder:8.0` 依赖管理
   - `sqlite-jdbc` 固定 3.45.3.0 移除，跟随 BOM（3.49.1.0）
   - 新增 `hibernate-community-dialects`（BOM 管理，无版本）
3. 测试策略采用 R2 的内存库方案。
