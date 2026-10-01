# Phase 0 Research: 002-react

## R1: core 引入 Spring AI 数据模型的确切 artifact（拍板③落地）

- **Decision**: `axion-core` 增加 Spring AI 数据模型依赖，边界 = "可用其纯数据模型（Prompt / ChatResponse / ToolCall / ToolDefinition），禁用 Agent 抽象与自动 tool 执行"。实施第一步先 `mvn dependency:tree` 核实：锁定 BOM 中承载 `org.springframework.ai.chat.prompt.Prompt`、`org.springframework.ai.chat.model.ChatResponse`、`org.springframework.ai.model.tool.ToolCallingChatOptions`、`org.springframework.ai.tool.definition.ToolDefinition` 的确切 artifact（`spring-ai-model` 或同级数据模型构件），再决定 core pom 的依赖写法。
- **T001 核实结论（2026-09-01，本地仓库 jar 反查实测）**: `spring-ai-model:1.1.8`（锁定 spring-ai-bom 管理）同时承载全部四个类型（`chat/prompt/Prompt`、`chat/model/ChatResponse`、`model/tool/ToolCallingChatOptions`、`tool/definition/ToolDefinition`）——core pom 只需这一个数据模型构件，不引入 starter/自动配置。
- **Rationale**: 需求文档修订说明二拍板③；001 已实测这些类型在 1.1.8 中可解析（见 001 research.md R1）。core 不引入 starter（starter 会带自动配置与连接器，违反边界）。
- **Alternatives considered**: 在 core 手写平行数据模型再在 provider 转换 → 被否决（重复造轮子 + 转换层爆炸）；ReActLoop 挪到 provider 模块 → 被否决（技术方案 §10 模块表：ReActLoop 属 core，G2-5）。

## R2: ReActLoop（core）消费 ProviderService（provider）的跨模块机制 —— 已拍板（方案 A，2026-09-01 G2）

**摩擦点**：需求文档 FR-1 骨架 `providerService.chat(session.id(), profile, prompt)` 要求 ReActLoop 直调 `ProviderService`；但依赖方向铁律 core ← provider（CLAUDE.md 依赖方向、技术方案 §10），core 不得反向依赖 provider——否则 Maven 模块循环（provider 已 import core 的 Profile）。

| 方案 | 形态 | 代价 |
|------|------|------|
| A（推荐） | core 定义端口接口 `LlmGateway`（签名 = `ChatResponse chat(String sessionId, Profile profile, Prompt prompt)`，与 ProviderService.chat 完全一致）；ReActLoop 依赖端口；ProviderService 加一行 `implements LlmGateway`（方法体零改动）；boot/测试装配时以 ProviderService 实例注入 | 新增 core 公共类型 LlmGateway（交付清单之外 → 停止清单第 1 条）+ 触碰 001 ProviderService 类声明（停止清单第 4 条）——需用户确认 |
| B | ReActLoop 保持持有 ProviderService 具体类型 → core pom 依赖 provider | 模块循环依赖，Maven 构建直接失败 —— 不可行 |
| C | ReActLoop 挪到 axion-provider | 违反技术方案 §10 模块表（ReActLoop 属 core）→ G2-5 不过 —— 不可行 |

- **Rationale（推荐 A）**: 依赖倒置是既有架构（技术方案 §8.5 ScheduledTaskStore「契约在 core、实现在 storage」同款先例）；签名逐字一致，001 契约零破坏；后续换 Provider 实现/测试替身都从端口走。
- **拍板结果（2026-09-01 G2，用户确认）**: **方案 A**——core 定义 `LlmGateway` 端口接口，ProviderService 加一行 `implements`。需求文档 002 交付清单已补列 `LlmGateway`，改造点已记录 ProviderService 声明变更；001 需求文档补修订说明。

## R3: PromptBuilder（core）复用 ToolSchemaAdapter（provider）—— 已拍板（方案 A，2026-09-01 G2）

**摩擦点**：需求文档 FR-2 要求"复用 001 的 ToolSchemaAdapter"做工具列表翻译；但 ToolSchemaAdapter 在 axion-provider，PromptBuilder 在 core——同 R2 的依赖方向问题。实测（2026-09-01 grep）：ToolSchemaAdapter 的生产消费方当前为**零**（只有自身 ToolSchemaAdapterTest 引用；ProviderService 不调它）——它从 001 起就是为 PromptBuilder 预留的翻译器。

| 方案 | 形态 | 代价 |
|------|------|------|
| A（推荐） | ToolSchemaAdapter 从 axion-provider **迁到 axion-core**（类 + ToolSchemaAdapterTest 随迁，逻辑零改动；provider 无生产代码引用它，只删文件） | 移动 001 已交付类 = 触碰前序交付物（停止清单第 4 条，002 需求文档未列改造点）——需用户确认；CLAUDE.md 模块表 axion-provider 描述去掉"Function Calling 适配"字样（文档同步） |
| B | core 定义翻译端口接口，provider 的 ToolSchemaAdapter 加 `implements` | 又一个新端口 + 001 触碰，类仍留在 provider —— 比 A 多一层间接，无收益 |
| C | PromptBuilder 内嵌同源翻译逻辑（私有方法，001 不动） | 两份同源逻辑漂移；001 的 ToolSchemaAdapter 沦为无生产消费方的死代码；"复用"名不副实 |

- **Rationale（推荐 A）**: 单一真相源、零死代码；ToolSchemaAdapter 只依赖 AxionTool + ObjectMapper + ToolDefinition（纯数据模型）——拍板③后 core 已具备承载条件；改动半径 = 2 个文件搬家 + 模块描述同步。
- **拍板结果（2026-09-01 G2，用户确认）**: **方案 A**——ToolSchemaAdapter + ToolSchemaAdapterTest 迁往 axion-core（逻辑零改动）。需求文档 002「改造点」已补列；CLAUDE.md 与 TechnicalSolution §10 模块表已同步（core 增 ToolSchemaAdapter/LlmGateway，provider 去 Function Calling 适配）；001 需求文档补修订说明。

## R4: ChatResponse → Message 转换落位（Session 保持框架无关）

- **Decision**: 转换是 `ReActLoop` 的私有方法（唯一调用方）：`ChatResponse` → `Message.assistant(content, toolCalls)`（toolCall 逐项映射为 `Message.ToolCallRequest(id, name, arguments)`）；工具执行结果 → `Message`（role=TOOL，`ToolCallResult(toolCallId, content)`）。`Session` 内只存框架无关的 `Message`（001 已交付，含 ToolCallRequest/ToolCallResult 嵌套类型与防御性拷贝），第 18 节 JSON 序列化落库无需再转换。
- **Rationale**: 需求文档「类型落地说明」（拍板 2026-09-01）："session.append(resp) 落地时把 ChatResponse 转成 core 的 Message 再累积——Session 历史保持框架无关、可 JSON 序列化"。
- **Alternatives considered**: Session 直接存 ChatResponse → 被否决（框架类型进会话历史，第 18 节落库耦合 Spring AI 版本）。

## R5: tool_invocations 手工建表（与 001 llm_calls 同口径）

- **Decision**: `schema.sql` **增量追加** `tool_invocations` DDL（不覆盖 001 的 `llm_calls` 段）；字段照 CLAUDE.md 表：`id`（BIGINT PK 自增）、`session_id`、`tool_name`、`input_json`、`result_json`、`success`（BOOLEAN）、`error_message`（TEXT 可空）、`duration_ms`（BIGINT）、`created_at`（TEXT，ISO-8601，复用 001 `InstantTextConverter`）。`ToolInvocationRepositoryTest` 显式执行同一份 schema.sql（坑八：不用 Hibernate 自动建）。
- **Rationale**: 技术方案 §9.2 工程风险提示 + 001 已批准口径（001 research.md R2）；测试与生产同一脚本，防"测试绿了、生产列名对不上"。
- **Alternatives considered**: 依赖 ddl-auto 自动建表 → 被否决（SQLite ALTER TABLE 弱）；引入 Flyway → 核心阶段不引入额外组件。

## R6: SessionManager 最小契约（内存版，拍板①落地）

- **Decision**: `Session`（core）= id + profileName + channel + userId + messages（累积容器，append 追加）；`SessionManager`（core）= `getOrCreate(channel, user, profileName)` / `get(id)` / `save(session)`，内存 `ConcurrentHashMap<String, Session>`（虚拟线程并发安全）；**session_id 拼接只发生在 SessionManager 内一处**（H4 不变量四，`SessionManagerTest` 架构断言钉死）；`save` 内存版为 no-op 占位（契约留给第 18 节 JPA 化）。幂等：同一三元组两次 getOrCreate 返回同一实例。
- **Rationale**: 需求文档 FR-6 + 修订说明二拍板①（"Session 最小契约随节交付，sessions 表与 JPA 持久化仍归第 18 节"）；课件第18节契约提前补位。
- **Alternatives considered**: 回改 001 补 Session → 被否决（001 已 review，不回改；以本节交付补缺口）。

## R7: 历史截断的"轮"定义（坑二）

- **Decision**: 一轮 = 一条 USER 消息 + 随后 ASSISTANT 响应 + 该响应携带的全部 TOOL 结果消息。截断从尾部回数保留最近 N 轮（默认 20，`Profile.Settings.maxHistoryTurns` 001 已建全）；**TOOL 消息跟随其所属 ASSISTANT 响应成组保留**——不切断一轮内的 tool 调用链（坑二回归测试钉死）。system 段不在截断范围。
- **Rationale**: 需求文档 FR-2/坑二 + 技术方案 §4.3（"保留 system prompt 和最近 N 轮对话"）。
- **Alternatives considered**: 按消息条数截断 → 被否决（会把 tool 调用链拦腰切断，模型上下文错乱）。

## R8: ContextLoader 读取边界（坑五）

- **Decision**: `ContextLoader`（core）构造时注入工作区根路径；`load(Profile)` 每轮现读：① `Profile.bootstrap` 引用的文件（如 AGENTS.md/SOUL.md/USER.md）——显式引用缺失**报错**、bootstrap 文件缺失至少 **WARN**；② 当前 Agent `skills/` 软连接集合（`.axion/agents/<name>/skills/<name>` → `.axion/skills/<name>/SKILL.md` frontmatter 的 name/description），只注入元数据不预载正文（宪法 IV 渐进披露）。无任何缓存。skills 目录不存在 = 跳过（不报错）。测试用 JUnit `@TempDir` 搭临时工作区。
- **Rationale**: 需求文档 FR-3（两条铁律）+ 宪法 IV + 技术方案 §8.3。
- **Alternatives considered**: 缓存文件内容 → 被否决（坑五：用户改完不生效）；AGENT.md 正文注入 → 被否决（归第 29 节，本节不交付）。

## R9: 测试栈与跑法

- **Decision**: 全部 JUnit 5 + Mockito 单测（001 测试栈）；无集成冒烟——本节交付物全部不碰网络（模型 mock、工具 mock、文件用 @TempDir），`mvn test` 秒级全跑；坑六（无 Spring AI 自动执行路径）用行为回归：mock ChatModel 返回 tool call，断言工具经 ToolExecutor 恰好执行一次（不是两次）且响应中无框架自动执行痕迹。
- **Rationale**: 需求文档验收标准（"本节的东西全部不碰网络，harness 全是单测"）；真模型验证归人工部分（Demo 一对话版）。
- **Alternatives considered**: 引入 ArchUnit → 被否决（001 未引入，本节沿用行为断言即可钉死）。

## R10: 骨架签名与默认值

- **Decision**: `ReActLoop.run(Session, String userMessage, Profile)` → String；`AgentService.process(Session, String)` → String（Profile 从 `ProfileRegistry.findByName(session.profileName())` 取）；`ToolExecutor.execute(String sessionId, ToolCall)` → ToolResult（ToolCall 为 Spring AI 数据模型，拍板③）。最大轮数/历史截断默认值复用 001 已建全的 `Profile.Settings.DEFAULT_MAX_ITERATIONS(10)` / `DEFAULT_MAX_HISTORY_TURNS(20)`（001 跨节契约：settings 一次建全）。
- **Rationale**: 需求文档 FR-1/FR-5 核心代码骨架（课件第17节一致）+ 001 交付物实测（Profile.Settings 常量已存在）。
- **Alternatives considered**: 另建默认值常量 → 被否决（重复定义，漂移风险）。
