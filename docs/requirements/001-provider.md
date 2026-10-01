# Agent Provider 模块设计文档

> 需求编号：001-provider | 对应主体阶段 US-1（对接 LLM，核心能力一）
> 文档依据（唯一来源）：`docs/AiProgrammingGuide.md` §4.1、`docs/TechnicalSolution.md` §1.1/§3/§8.2/§9.2、`docs/DemandAnalysis.md` §5.3/§6.1/§10/§13、`docs/IndustryResearch.md` §4.3~4.4
>
> 修订说明（2026-09-01，002-react G2 拍板）：① `ToolSchemaAdapter` 迁至 axion-core（类+测试随迁，逻辑零改动；002 的 PromptBuilder 在 core 消费它，依赖方向 core ← provider 下无法反向引用）；② `ProviderService` 增加 `implements LlmGateway`（core 定义的依赖倒置端口，签名逐字一致，既有调用方不受影响）。

## 背景与价值

Java 生态在 Agent OS 这一层是缺位的：OpenClaw 是 Node.js、Hermes Agent 是 Python，Java 企业要引入 Agent 底座要么换语言栈、要么自己造（IndustryResearch §4.3）。而底层 LLM 调用这件事 Java 生态已经解决了——Spring AI Alibaba 提供十余个主流 LLM（DeepSeek、通义、Kimi、智谱等）的 connector（IndustryResearch §4.4），Axion 不需要重复造轮子（IndustryResearch §5.1：跟 Spring AI 是复用关系，不是竞争关系）。

Axion 的定位决定了一个硬需求：**企业常常同时采购多家模型**（不同部门、不同业务、不同合规要求），底座必须让多个 LLM Provider 并存，Agent 按配置选用，且调用细节对上层完全透明。这就是 Provider 抽象（核心能力一）要解决的问题：在 Spring AI Alibaba 之上做一层薄包装，把"调哪家、怎么调"收口到 `ProviderService`，Agent 只感知"我调了一个模型"（技术方案 §3、需求文档 §5.3、编程指南 §4.1）。

一个必须点名的坑：多 Provider 并存时，Spring 容器里多个 `ChatModel` Bean 类型相同，**靠扫描容器区分 Provider 必然出错**——必须维护 provider name → `ChatModel` 的显式映射表（技术方案 §3.2）。这是本节最核心的架构决策。

## 用户场景

**场景一：运维助手（企业采购双模型）**
一家银行采购了 DeepSeek 和通义千问两家模型服务：运维助手 Agent 走 DeepSeek，客服 Agent 走通义。底座管理员在 `application.yaml` 里配置两个 Provider，两个 Agent 各自的 `AGENT.md` 里写不同的 `provider.name`，运行时互不干扰、互不歧义。（需求文档 §4 场景一、§5.3）

**场景二：Agent 作者不感知协议差异**
Agent 作者只写业务指令（`AGENT.md` 正文），不关心 DeepSeek 和 Kimi 的消息格式、Function Calling 协议有什么不同——协议转换被 Spring AI Alibaba 吸收，Agent 侧只有一份统一的调用结果结构。（技术方案 §3.1、编程指南 §4.1）

**场景三：合规审计要求每次模型调用可追溯**
严监管行业要求"系统必须完全可审计"。每次 LLM 调用——无论成功失败——都要落库：哪家 Provider、哪个模型、多少 token、耗时多久。审计数据从第一天就进 SQLite，而不是先靠日志、后期再反解析。（技术方案 §3.3、§9.2「相对原方案的调整」、需求文档 §10）

## 功能需求

> 从编程指南 §4.1（US-1 任务大类）与技术方案 §3 提炼。**交付物列是本节对外概念的白名单**，清单之外的新增对外概念必须停下报告。

| 编号 | 需求 | 交付物（落位模块） | 来源 |
|------|------|-------------------|------|
| FR-1 | **配置分两层，职责分开**：① 全局层 `application.yaml` 的 `axion.providers` 列表——声明这个实例接了哪些 Provider，每项有唯一 `name`、`api-key`（**只允许 `${ENV_VAR}` 环境变量占位，不允许明文**）、可选 `base-url`，解决"连不连得上"；② Profile 层（`AGENT.md` frontmatter 的 `provider` 段）——声明这个 Agent 用哪个 provider、哪个 model、什么温度，解决"这个 Agent 怎么用"。**Profile 引用的 `provider.name` 在全局层找不到同名项必须直接报错**，不得悄悄用错或留空跑过去。配置缺失或非法时启动校验给出清晰报错，不静默失败 | Provider 配置项 + `ProviderProperties` 配置类（axion-provider） | 技术方案 §3.1「Provider 配置模块」、§8.8 配置与密钥加载；两层分工与引用校验为实现级明确 |
| FR-2 | 提供 `ProviderService` 统一门面，对外只露一个方法：`chat(sessionId, Profile, Prompt)`——**必须带 `sessionId`**（审计记录要落到按 session 关联的 `llm_calls` 表，签名不带它审计就写不了）。按 Profile 的 provider name 选对应 `ChatModel` 完成调用，返回统一响应结构（模型文本 + toolCall 请求 + token 用量）；取不到模型抛 `ProviderNotFoundException`，不得悄悄用错。同步阻塞调用（虚拟线程承载并发） | `ProviderService`（axion-provider） | 技术方案 §3.1、§1.1 决策三；sessionId 参数为实现级明确 |
| FR-3 | **显式映射**：启动时按配置建立 `provider name → ChatModel` 的映射表（`Map<String, ChatModel>`）；`Profile.provider.name` 通过它路由。**禁止靠扫描 Spring 容器里的 `ChatModel` Bean 区分 Provider** | 映射表构建逻辑（axion-provider） | 技术方案 §3.2、编程指南 §4.1「关键注意」 |
| FR-4 | **Function Calling 适配**：把 Axion 内部的 `AxionTool` 抽象转成 Spring AI 的工具调用格式（JSON Schema 生成 + 各家协议转换交给 Spring AI）。**只做格式转换，禁用 Spring AI 的自动 tool 执行**——tool 调用结果只透传回上层，由 ReAct 循环自己调度，否则 tool 会被调两次 | Function Calling 适配器（axion-provider） | 技术方案 §3.1、§1.1 决策二（最容易埋 bug 的一条） |
| FR-5 | **审计落库（day one）**：每次 LLM 调用——成功和失败都——写入 `llm_calls` 表：`session_id`、`provider`、`model`、`prompt_tokens`、`completion_tokens`、`total_tokens`、`duration_ms`、`created_at`（ISO-8601 TEXT 存储）、**`success`、`error_message`**（后两列为对需求文档 §10 表结构的补充修订：没有它们，调用失败时事故在库里完全没有痕迹，与 `tool_invocations` 不对称；只记成功不记失败是最容易漏的一步） | `LlmCall` 实体 + `LlmCallRepository` + 手工建表脚本（axion-storage） | 技术方案 §9.2、需求文档 §10「LLM Call」；`success`/`error_message` 为补充修订 |
| FR-6 | `Profile` 核心数据结构：`name`、`description`、`identity`（`agent_name`、`prompt`）、`provider`（`name`、`model`、`temperature`）、`tools`、`mcp_servers`、`channels`、`schedules`、`bootstrap`、`settings`——**类本身本节一次建全**，后续各节用到哪个字段取哪个字段。基础版派生：从 `AGENT.md` frontmatter 派生 Profile（`deriveProfile`），注册进 `ProfileRegistry` 内存索引（启动扫描是当前唯一注册路径，后续节补运行时 `register()`）；**本节校验范围只有一条：provider 引用在全局层存在**，其余字段的校验规则归后续各节补；校验失败的 Agent 不阻断启动、记录错误日志 | `Profile`、`ProfileLoader`（基础版 deriveProfile）、`ProfileRegistry`（axion-core） | 技术方案 §8.2、需求文档 §10「Profile」 |
| FR-7 | `Message` 数据结构：承载 role / 文本内容 / toolCall 请求与结果，供 Provider 调用与后续 ReAct 循环复用 | `Message`（axion-core） | 编程指南 §4.1 核心抽象类 |
| FR-8 | `AxionTool` 接口骨架：`getName` / `getDescription` / `getInputSchema` / `execute`（本节只交付抽象与 schema 生成，内置实现归 US-4 核心能力四） | `AxionTool` 接口（axion-core） | 技术方案 §6.1、编程指南 §4.1 |
| NFR-1 | 全程同步阻塞，不引入 Reactor / WebFlux / CompletableFuture；并发由 Java 21 虚拟线程承担 | — | 技术方案 §1.1 决策三 |
| NFR-2 | 结构化 JSON 日志（Logback），LLM 调用日志与审计落库并存（日志不等价于审计） | — | 技术方案 §1.2 技术栈、§9.2 |
| NFR-3 | **职责边界划窄（正向定义）**：Provider 只做三件事——挑对模型、发起一次调用、把结果拿回来；循环怎么转、工具怎么执行、上下文怎么拼装都不归它管，否则 Provider 会越写越胖、最后和 ReActLoop 缠在一起分不开。本节代码不触碰 `ReActLoop`/`ToolExecutor` 等 US-2 内容；Provider 层不感知消息从哪个入口来 | — | 编程指南 §4.1/§4.2 边界、技术方案 §8.6 三种运行模式 |

![Agent Provider 调用链路：两层配置（全局层声明 + Profile 层引用校验）→ ProviderService 统一门面 → 显式映射选模型 → Spring AI 协议转换 → 各家 LLM；工具 schema 只翻译不执行；审计 day one 写 llm_calls](../../website/public/images/docs-provider-flow.svg)

**配置形态示例**（全局层 + Profile 层各管一段，key 一律 `${ENV}` 占位）：

```yaml
# application.yaml —— 全局层：声明有哪些 provider、凭证从哪来
axion:
  providers:
    - name: deepseek
      api-key: ${DEEPSEEK_API_KEY}
    - name: kimi
      api-key: ${KIMI_API_KEY}
```

```yaml
# AGENT.md frontmatter —— Profile 层：这个 Agent 具体怎么用
provider:
  name: deepseek        # 必须能在全局层的 providers 列表里找到同名项
  model: deepseek-chat
  temperature: 0.7
```

## 明确不做

> 来源：需求文档 §5.3「核心阶段不做」+ §6.1 扩展功能、技术方案 §3.3「关键设计点」。

- **Provider fallback / 自动切换**：Provider 故障时直接报错给 Agent，不做备用 Provider 切换（扩展阶段通过 Profile 的 `fallback` 字段实现）
- **hedge racing / circuit breaker / 三层 failover**：全部放扩展阶段（需求文档 §6.1）
- **Adaptive Routing 动态路由**：按任务类型/历史质量/负载的自动选型，放扩展阶段（需求文档 §6.1）
- **成本聚合与 Web 看板**：核心阶段只把 token/耗时落 `llm_calls` 表，完整成本聚合放扩展阶段（技术方案 §3.3）
- **认证 / 限流 / RBAC / 多租户**：Web 层治理项，核心阶段不做（技术方案 §7.5）
- **SSE 流式响应**：放扩展阶段（技术方案 §1.1 决策三）
- **Provider 管理命令**（`axion provider list` 等）：归后续 CLI 节（技术方案 §8.7 命令行工具），本节不交付
- **重试与超时高级策略**：核心阶段只做基础超时与直报错误，不做指数退避等策略（技术方案 §3.3）

## 验收标准

### 自动化部分（测试套件承载，`mvn clean verify` 全绿即通过）

需求文档 §13 功能验收要求：核心功能全部完成，**每个功能模块至少有一个端到端测试用例覆盖**，其中 Provider 项是"**至少跑通 DeepSeek 和 Kimi 两个**"。

**测试分层**（判断标准：要不要碰真实网络）：
- **单测（默认全跑）**：路由、校验、审计、翻译全部 mock `ChatModel`，不花一分钱、不依赖网络，秒级跑完——harness 的主体
- **集成冒烟 `ProviderSmokeIT`（打 `@Tag("integration")`，本地手动跑）**：读环境变量里的真 key、真调一次、断言非空响应且 `llm_calls` 多一条 `success=true`；CI 默认跳过（外部 API 的可用性不得成为自己流水线的可用性）

**四个单测类，逐条对应验收点**——**每个在"功能需求"中点过名的坑，都必须有一个对应回归测试钉死**（坑一显式映射、坑二关自动执行、失败审计路径，各一条）：

| 测试类 | 关键回归点 |
|--------|-----------|
| `ProfileLoaderTest` | `AGENT.md` frontmatter → `Profile` 全字段解析；引用全局层不存在的 provider 报错清晰；坏文件不阻断其余加载、有错误日志；`${ENV}` 占位从环境变量解析 |
| `ProviderServiceTest` | ① 双 provider 按名路由**不串台**（`verify(kimi).call` + `verify(deepseek, never()).call`）② 未知名抛 `ProviderNotFoundException` ③ **成功/失败都落审计**——断言的不是"没抛异常"，而是"抛了异常**并且**审计先落了账"（`success=false` + 原因）④ **自动执行关闭**（ArgumentCaptor 捕获请求，断言 `autoExecuteTools=false`——一旦有人改回自动执行，测试立刻红）⑤ 架构断言：代码中不存在扫描 `ChatModel` Bean 集合的路径（守住技术方案 §3.2） |
| `ToolSchemaAdapterTest` | `AxionTool` → Spring AI 格式后字段一一对齐（name/description/parameters）；**只翻译**——产物中不含任何执行逻辑 |
| `LlmCallRepositoryTest` | **测试里执行手工 `schema.sql` 建表，不用 Hibernate 自动建**（否则测试绿了、生产跑真脚本列名对不上白测）；`llm_calls` 能存能读，`success`/`error_message` 两列真实存在 |

跑法：`mvn test` 日常全跑（全绿才算实现完成）；`DEEPSEEK_API_KEY=xxx mvn test -Dgroups=integration` 手动冒烟验真连通。

### 人工部分（做完怎么验）

- **集成冒烟真跑过一次**：`mvn -pl axion-provider -am test -Dtest.groups=integration -Dtest.excludedGroups=`（bash 前缀 `DEEPSEEK_API_KEY=xxx`；PowerShell 先 `$env:DEEPSEEK_API_KEY = "xxx"` 再跑 mvn）跑通 `ProviderSmokeIT`，拿到过真实响应（需求文档 §13 要求两家都跑通；无 key 时先跑通一家并记录待办）
- 打开 `.axion/axion.db` 核对 `llm_calls`：token 数与 API 实际返回一致，provider/model 字段正确
- 人工 review 显式映射实现：确认没有任何"扫描容器拿 ChatModel"的代码（技术方案 §3.2）
- 人工核对日志为结构化 JSON、无明文 key 落盘（技术方案 §8.8）

## 依赖与假设

### 前序交付物（已就位，本节直接依赖）

- **工程地基**：9 模块 Maven 骨架、JDK 21 + Spring Boot 3.x BOM、Logback 结构化 JSON、静态检查门禁（Checkstyle/PMD 已跑通）、应用入口 `AxionApplication`——本节不重复搭地基
- 现状确认：`axion-core` / `axion-storage` / `axion-provider` 当前均为空壳（仅 package-info），FR-5/6/7/8 的实体与抽象是这三个模块的第一批业务代码

### 外部依赖与假设

> 补充修订（2026-08-30 S6 实施期，停止清单第 6 条处置已获用户确认）：新增
> `com.github.spotbugs:spotbugs-annotations:4.10.4`（**provided 编译期注解**，非运行时依赖，
> 仅用于对"失败先落账、异常原样上抛"这一契约必需形态做带理由的 SpotBugs 局部抑制；
> SpotBugs 门禁的 effort=Max/threshold=Low 与无 excludeFilter 的现状下无其他零依赖合规写法）。

- **Spring AI Alibaba**：BOM 已锁定，但两点必须先验证——① `@Tool` 注解与 tool calling 相关 API 的确切名称以锁定的版本为准（技术方案 §1.1 决策四明确要求研发前对当前版本核实）；② **每家模型背后是独立的 starter 依赖**，某些 milestone 版本的 Spring AI BOM 未必包含每一家——文档里的 `deepseek`/`kimi` 只是示意名，接哪家先 `mvn dependency:tree` 确认依赖能下载、能解析，不要照着名字假设一定能用
- **SQLite + Spring Data JPA**：依赖由 BOM 提供；`hibernate.ddl-auto=update` 在 SQLite 上 ALTER TABLE 支持弱，`llm_calls` 表结构一次定对，后续演进用手工建表脚本（技术方案 §9.2 工程风险提示）
- **运行时环境**：`DEEPSEEK_API_KEY` / `KIMI_API_KEY` 由用户以环境变量注入（人工验收时提供）；网络可达所选 Provider 的 API 端点
- **跨节契约**：本节交付的 `ProviderService` 签名是下一节 US-2（ReAct 循环，编程指南 §4.2）的调用契约——后续改动必须视为"修改前序节交付的公共接口"，需停下报告
- **跑通标准**：Provider 自身没有独立入口，它要和下一节 US-2（ReAct 循环）一起才能撑起 Demo 一（每日天气）的对话版——本节的跑通标准就是"能撑住 Demo 一里的那次大模型调用"（技术方案 §12.1）
