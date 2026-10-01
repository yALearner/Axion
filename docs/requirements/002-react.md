# ReAct 循环模块设计文档

> 需求编号：002-react | 对应主体阶段 US-2（ReAct 循环，核心能力二）
> 文档依据：`docs/AiProgrammingGuide.md` §4.2、`docs/TechnicalSolution.md` §1.1/§2/§4/§8.3/§9.2、`docs/DemandAnalysis.md` §5.4/§5.9/§9/§13、`docs/IndustryResearch.md` §1.1/§5.4（权威设计源）；课件《第17节：ReAct 原理解析、实现与代码讲解》（course repo `docs/class/`，实施级事实源）
>
> 修订说明（2026-09-01）：本版向课件第17节对齐——① 范围收窄至课件"本节交付物"（CLI/init/Session 内存版/最小 Tool 体系/Sandbox 移出，归第 18/20/23/24 节）；② 补齐课件独有的实现级细节（签名骨架、强制结束文案、缺失报错铁律、坑四 ThreadLocal 泄漏、AgentServiceTest/ContextLoaderTest、约定条目）；③ 课件与四文档的冲突点经用户拍板**一律参照课件**（唯一例外：课件"Profile 的 skills 字段"表述按宪法 IV 以软连接为准，见 FR-3）。修订依据见对话记录。
>
> 修订说明二（2026-09-01，实施前拍板）：① Session 前序缺口——**随本节交付最小 Session 契约**（core 的 `Session` 数据结构 + `SessionManager` 最小契约，见 FR-6；sessions 表与 JPA 持久化仍归第 18 节）；② **本节交付 Sandbox 纯接口**（`Sandbox`/`SandboxAction`/`ActionType`/`SandboxViolationException`，落位 axion-tool，见 FR-7；`WhitelistSandbox` 实现与三层白名单归第 23/24 节）；③ **core 引入 spring-ai 数据模型依赖**——边界为"可用其纯数据模型（Prompt/ChatResponse/ToolCall），禁用其 Agent 抽象与自动 tool 执行"（宪法 I/II 不变；001 review 中"core 保持框架无关"表述已同步修订）。修订依据见对话记录。

## 背景与价值

一句话：**ReAct 就是让大模型像人做事一样，在一个循环里反复"想一步、做一步、看结果"，直到把事办成**（课件 §一）。单独调一次大模型只是个 chatbot——你问一句、它答一句；但"看看今天天气，帮我决定穿什么"这种任务，模型得先去查天气、拿到结果、再根据结果给建议。ReAct 把"想—做—看"串成一个循环：模型想下一步该干嘛、调个工具去做、拿到结果看一眼，不够就再来一轮，够了就给最终答复。这个模式 2022 年提出，现在是事实标准——Claude Code、Cursor、LangChain 跑的都是它（课件 §一）。

在 Axion 里，ReAct 是那个"大脑循环"：它自己不调模型、也不执行工具，而是指挥——想的时候通过上一节的 Provider 调一次大模型，做的时候把工具交给 ToolExecutor（课件 §一；技术方案 §2 把它放在引擎层，"Provider、Memory、Tool 三个能力供养 ReAct 循环这个引擎"）。它对 Axion 定位的意义是直接的：Axion 做运行时、不做编排（IndustryResearch §5.4），没有显式流程图，Agent 在运行时自己决定下一步——ReAct 循环就是"不做编排"的技术支点（需求文档 §5.4）。所以它是"Axion 最关键的一段代码"（需求文档 §5.4、编程指南 §4.2）。

两个技术决策决定了它怎么写：

1. **自实现，不用框架现成的循环**。Spring AI 这类框架都带现成的 Agent/循环封装，拿来就能跑。但循环恰恰是 Agent 最需要自己掌控的地方——什么时候停、工具失败了怎么办、上下文太长了怎么压、哪几步想换个模型，这些都得能自己调；用框架的黑盒，这些就动不了（课件 §二）。所以核心阶段自己写这几十行，把控制权攥在手里（技术方案 §1.1 决策一、宪法原则 I）。
2. **禁用 Spring AI 的自动 tool 执行**——否则 tool 会被调两次。执行权必须收在 ToolExecutor 一处，不能有第二条路（决策二、宪法原则 II；课件 §三）。

ReAct 有个反直觉的地方：它是 Agent 的灵魂，但主循环的代码其实很短，就几十行。难的不是"写个 while 循环"，而是循环里要照顾的那些边界。所以动手前先把职责拆干净：**循环本身只做一件事——调度**。它负责转圈、判断该不该停、把每轮的结果攒起来；每轮要拼的 prompt、要调的模型、要执行的工具，全都交出去。循环里塞的东西越少，它越好读、越不容易出 bug（课件 §二）。上一节（001-provider）已交付 ProviderService，本节把它拼成 Agent 大脑——这也是 001 没做独立 Demo 的原因：Provider 要和 ReAct 一起才能撑起 Demo 一（编程指南 §4.1/§4.2）。

## 用户场景

**场景一：查天气穿什么（Demo 一的对话版，本节验收场景）**
用户输入"查一下北京天气并告诉我穿什么"。Agent 第一轮思考后调用 `http_get` 拉天气 JSON，看结果后第二轮生成穿衣建议回复。整个过程不是写死的流程——是 Agent 在运行时自己决定的（课件 §五、编程指南 §4.2 验收 Demo 一）。

**场景二：多步骤任务一次对话内连续完成**
Agent 自主决定何时调用哪个工具，多步骤任务一次对话内连续完成（先读文件、再分析、再调 API、再生成报告），业务方不需要写死流程（需求文档 §5.4）。

**场景三：出错时自己纠偏**
工具调用失败时，Agent 能看到失败结果并在下一轮自己重试、换工具，而不是把错误直接抛给用户（需求文档 §5.4）。纠偏由 LLM 的下一轮思考完成——ToolExecutor 只如实回报结果，不替 Agent 做决定。

**场景四：可审计的完整思考链**
Session 的对话历史包含完整的 LLM 调用链和 Tool 调用链（技术方案 §4.3），一次对话里模型"想了什么、调了什么、拿到了什么"全部可查可审计。这是严监管企业"系统必须完全可审计"要求落在 Agent 内循环上的体现（IndustryResearch §3.3）。

## 功能需求

> 从课件一、二部分提炼：课件第16/17节 + 编程指南 §4.1–4.2（US-1 交付边界 + US-2 任务大类）+ 技术方案 §3–4。**交付物列是本节对外概念的白名单**，清单之外的新增对外概念必须停下报告。

| 编号 | 需求 | 交付物（落位模块） | 来源 |
|------|------|-------------------|------|
| FR-1 | **`ReActLoop` 主循环（本节核心交付）**：签名 `run(Session session, String userMessage, Profile profile)`（课件 §三骨架为准；技术方案 §4.2"输入 Session 和用户消息"为简写）。七步：① 用户消息追加到 Session ② 组装 Prompt ③ 调 `ProviderService.chat(session.id(), profile, prompt)`——**传 session.id()**，llm_calls 按 session 关联审计 ④ 无 tool 调用 → 返回最终响应（**停止条件**：模型没提出要调工具，就说明它觉得能给最终答复了）⑤ 有 → 逐个交 `ToolExecutor.execute(session.id(), call)`，结果追加回 Session ⑥ 回到②继续 ⑦ **坑一（死循环）兜底**：达到 `maxIterations`（默认 10，Profile 覆盖）强制结束，返回 **"达到最大轮数，已停止"**。**坑三（不累积）防护**：每轮先把 LLM 响应存回 Session 再继续——事后能审计、下一轮接得上。自实现约数十行 Java，不触发 Spring AI 自动执行（宪法 I/II） | `ReActLoop`（axion-core） | 课件 §一/§二/§三；技术方案 §4.1/§4.2/§4.3；需求文档 §5.4 |
| FR-2 | **`PromptBuilder` 组装每轮 Prompt**，四部分按序：① system prompt = 角色设定（`Profile.identity.prompt`）+ 启动信息（Bootstrap 三件套，由 ContextLoader 提供）+ Skill 元数据，**末尾附当前日期时间**（模型自己不知道今天几号，定时场景的"今天"全靠这一行）② 长期记忆（Memory 模块提供的跨会话记忆——**没开就跳过**；Memory 模块归第 21/22 节，本节留拼接位）③ 会话历史（只留最近 N 轮，默认 20，超了截断——**坑二（context 撑爆）的解法**；长期记忆和会话历史是两码事，别混在一起说）④ 当前可用工具列表（Function Calling 格式，复用 001 的 `ToolSchemaAdapter`；工具注册归第 20 节，本节以注入的工具集支撑同一契约） | `PromptBuilder`（axion-core） | 课件 §三；技术方案 §4.2；编程指南 §4.2 |
| FR-3 | **`ContextLoader`（Bootstrap + Skill 元数据供给者）**：按 Profile 的 `bootstrap` 引用读 Bootstrap 三件套 + 当前 Agent 已绑定 Skill 的元数据，拼成 system prompt 第一部分。两条铁律：① **每次组装 prompt 重新读文件、不缓存**——用户改完立即生效；② **显式引用的文件缺失要报错、Bootstrap 缺失至少 WARN**——静默跳过会造成"人格悄悄丢了"这类最难查的软故障（**坑五**）。绑定真相源按宪法 IV 的软连接集合（课件原文"Profile 的 bootstrap 和 skills 字段"为过期表述，不采用字段式声明）。**AGENT.md 正文注入 system prompt 归第 29 节**（插件化 Agent），本节不交付 | `ContextLoader`（axion-core） | 课件 §三/§四；技术方案 §8.3；宪法 IV |
| FR-4 | **`ToolExecutor` 执行 LLM 返回的 tool 调用**：从工具表按名找到 `AxionTool` → 执行 → 结果包装成 `ToolResult` → **写 `tool_invocations` 审计表（day one，宪法 V）：成功要记、失败也要记**（`success`/`error_message` 与 llm_calls 同口径，**坑七**）。失败时错误信息带 `retryable` 回传（重试是 LLM 下一轮的决定，不内部自动重试）。**执行权唯一**：工具执行只在这一个地方发生——这就是上一节关掉 Spring AI 自动执行的原因，不能有第二条路。**沙箱检查不发生在 ToolExecutor 内**：涉外 IO 的 `enforce` 由各工具在 `execute` 首行自执行（技术方案 §6.7 原文；第 24 节改造点先例），ToolExecutor 不持 Sandbox 引用——core 不反向依赖 axion-tool（见 FR-7） | `ToolExecutor`（axion-core）+ `ToolInvocation` 实体、`ToolInvocationRepository`、schema.sql 增量（axion-storage） | 课件 §三；技术方案 §4.2/§6.7/§9.2；需求文档 §10 |
| FR-5 | **`AgentService` 统一入口 + `ProfileContext`**：`process(Session session, String userMessage)` 是三种触发源共用的编排者（宪法 VIII）——Profile 放进 `ProfileContext`（ThreadLocal，虚拟线程下每请求独立）→ `ReActLoop.run` → `sessionManager.save(session)` → **finally 清理 `ProfileContext`**。**坑四（ThreadLocal 泄漏）**：处理抛异常也必须清——泄漏在单请求测试里永远不报错，只在并发复用时串号，是最阴险的一类 bug。`AxionTool.execute` 签名不带 Profile，工具执行时靠 `ProfileContext` 知道"当前是哪个 Agent"，不改工具接口。**本节交付编排者本体，触发源接入从第 18 节（CLI）开始**——`ReActLoop` 不感知消息从哪个入口来 | `AgentService`、`ProfileContext`（axion-core） | 课件 §三/§四；技术方案 §4.2/§8.5、宪法 VIII |
| FR-6 | **Session 最小契约（前序缺口补位，经拍板随本节交付）**：课件第17节以 Session 为输入，但 Session 归第 18 节交付——本节交付最小契约补上前序缺口：`Session` 数据结构（对话历史累积容器，历史以 001 的 `Message` 承载，可序列化、第 18 节落库用）+ `SessionManager` 最小契约（`getOrCreate(channel, user, profileName)` / `get` / `save`，**session_id 只在 SessionManager 内一处拼接**——H4 不变量四）。内存版实现；sessions 表 + JPA 实体 + 跨重启恢复仍归第 18 节 | `Session`、`SessionManager`（axion-core） | 课件第18节（提前交付的最小契约）；需求文档 §5.9；技术方案 §9.2 |
| FR-7 | **Sandbox 接口墙（经拍板随本节交付，纯抽象零实现）**：`Sandbox.enforce(SandboxAction)` 单方法、`ActionType` 四值（FILE_READ \| FILE_WRITE \| SHELL_COMMAND \| HTTP_REQUEST）、校验失败抛 `SandboxViolationException`——字面量照技术方案 §6.7，落位 axion-tool（宪法 IX 三合一）。本节无人调用、无白名单配置：涉外 IO 的 enforce 由各工具在 `execute` 首行接入（第 20 节起），`WhitelistSandbox` 实现与三层白名单归第 23/24 节。接口先立，H4 不变量一的接线从第 20 节开始落地 | `Sandbox` 接口、`SandboxAction`、`ActionType`、`SandboxViolationException`（axion-tool） | 技术方案 §6.7、宪法 VI/IX；课件 §三（"23、24 节细讲"） |
| NFR-1 | 全程同步阻塞，不引入 Reactor / WebFlux / CompletableFuture；并发由 Java 21 虚拟线程承担 | — | 技术方案 §1.1 决策三、宪法 VII |
| NFR-2 | 结构化 JSON 日志（Logback）：每次 LLM 调用和 Tool 调用都记录结构化日志，日志与审计落库并存（日志不等价于审计） | — | 需求文档 §5.4、技术方案 §1.2 |
| NFR-3 | **职责边界划窄（正向定义）**：循环本身只做调度——转圈、判断停不停、攒结果；拼 prompt、调模型、执行工具全都交出去。循环里塞的东西越少，越好读、越不容易出 bug | — | 课件 §二、技术方案 §2 |

![ReAct 循环全链路：三种触发源汇入 AgentService 统一编排（ProfileContext set/finally clear）→ PromptBuilder 每轮组装（ContextLoader 不缓存供给）→ ReActLoop 调度循环 → 无 tool call 返回最终响应 / 有 tool call 交 ToolExecutor 执行并回填继续循环；审计 day one 双表落账](../../website/public/images/docs-react-flow.svg)

### 核心代码骨架（与课件第17节一致）

```java
public String run(Session session, String userMessage, Profile profile) {
    session.append(userMessage);
    for (int i = 0; i < profile.maxIterations(); i++) {   // 默认 10，防死循环（坑一）
        Prompt prompt = promptBuilder.build(session, profile);
        Response resp = providerService.chat(session.id(), profile, prompt);  // sessionId 供审计关联
        session.append(resp);                              // 累积，可审计（坑三）
        if (!resp.hasToolCalls()) {
            return resp.text();                            // 没有工具调用，收尾
        }
        for (ToolCall call : resp.toolCalls()) {
            ToolResult result = toolExecutor.execute(session.id(), call); // 执行权在这，唯一入口
            session.appendToolResult(result);
        }
    }
    return "达到最大轮数，已停止";
}
```

```java
public String process(Session session, String userMessage) {
    Profile profile = profileRegistry.get(session.profileName());
    ProfileContext.set(profile);          // 工具执行时靠它知道"当前是哪个 Agent"
    try {
        String reply = reActLoop.run(session, userMessage, profile);
        sessionManager.save(session);     // 把累积完的历史持久化
        return reply;
    } finally {
        ProfileContext.clear();           // 虚拟线程每请求独立，用完必须清（坑四）
    }
}
```

**类型落地说明**（拍板 2026-09-01）：骨架中的 `Prompt`/`Response`/`ToolCall` 是 Spring AI 数据模型（`Prompt`/`ChatResponse`/`ToolCall`）——core 引入 spring-ai 数据模型依赖，边界为"可用其纯数据模型、禁用其 Agent 抽象与自动 tool 执行"（宪法 I/II）。`session.append(resp)` 落地时把 `ChatResponse` 转成 core 的 `Message`（`ToolCallRequest`/`ToolCallResult` 嵌套）再累积——Session 历史保持框架无关、可 JSON 序列化（第 18 节落库）。

### 本节交付物清单（Spec-Kit 拆解锚点 / axion-spec 交付清单比对基准）

- **代码**：`ReActLoop`、`PromptBuilder`、`ToolExecutor`、`AgentService`、`ProfileContext`、`ContextLoader`、`Session`、`SessionManager`（最小契约）、`LlmGateway`（axion-core 依赖倒置端口，G2 拍板 2026-09-01：签名 = `ChatResponse chat(String sessionId, Profile profile, Prompt prompt)`，与 ProviderService.chat 完全一致，ReActLoop 依赖端口而非具体类）、`Sandbox` 接口 + `SandboxAction` + `ActionType` + `SandboxViolationException`（axion-tool）、`ToolInvocation` 实体 + `ToolInvocationRepository`
- **测试**：`ReActLoopTest`、`PromptBuilderTest`、`ToolExecutorTest`、`AgentServiceTest`、`ContextLoaderTest`、`SessionManagerTest`（最小契约：id 拼接只此一处 + getOrCreate 幂等）、`ToolInvocationRepositoryTest`（沿用第16节 `LlmCallRepositoryTest` 的 schema.sql 同口径讲究；课件17节 harness 未单列，按课程既有模式补齐）
- **表**：`tool_invocations`（含 `success`/`error_message` 列，手工建表脚本）
- **约定**：最大轮数默认 10；历史截断默认 20 轮；prompt 末尾附当前日期时间

## 改造点（经用户拍板允许修改的前序公共接口）

> G2 门禁中发现的跨模块依赖摩擦点，2026-09-01 经用户逐项拍板（方案 A：端口接口 / 方案 A：迁移）：

1. **`ToolSchemaAdapter` 自 axion-provider 迁移至 axion-core**（类 + `ToolSchemaAdapterTest` 随迁，逻辑零改动）：FR-2 要求 PromptBuilder（core）复用翻译能力，但依赖方向铁律 core ← provider 禁止反向依赖；实测其生产消费方为零（仅自身测试引用），迁移零破坏。CLAUDE.md 与 `TechnicalSolution.md` §10 模块表已同步修订。
2. **`ProviderService` 增加 `implements LlmGateway`**（类声明一行，方法签名零改动）：ReActLoop（core）经 `LlmGateway` 端口调 LLM，装配时以 ProviderService 实例注入；001 既有调用方不受影响。

## 明确不做

> 来源：课件"有几样先别做" + 需求文档 §5.4/§6.1「核心阶段不做」+ 技术方案 §4.3 + 课件分节归属。

- **工具并行调用、Agent 之间互相委托、流式输出、上下文压缩**：课件点名的四样，核心阶段都不做；上下文先用"只留最近 N 轮"简单办法顶着，压缩留扩展阶段（课件 §三、技术方案 §4.3、需求文档 §5.4）
- **ToolExecutor 内部自动重试（指数退避）**：失败信息带 `retryable` 回传，重试是 LLM 下一轮的决定（技术方案 §4.2，见 FR-4）
- **Tool 体系（ToolRegistry 完整形态、内置 Tool http_get 等）**：归第 20 节（课件分节）；本节 ToolExecutor/PromptBuilder 以注入的工具集支撑契约
- **WhitelistSandbox 实现与三层白名单（文件/Shell/HTTP）**：归第 23/24 节（课件 §三明示）；本节只交付 Sandbox 纯接口（FR-7），无实现、无白名单配置
- **Session 持久化（sessions 表 + JPA 实体 + 跨重启恢复）**：归第 18 节；本节交付的是 Session/SessionManager 最小契约（内存版，见 FR-6）
- **CLI Channel、chat / init 命令**：归第 18 节（课件分节）
- **AGENT.md 正文注入 system prompt**：归第 29 节（插件化 Agent）；本节角色设定来自 `Profile.identity.prompt`
- **Memory 模块（MemoryService / 长期记忆实现 / save_memory / recall_memory）**：归第 21/22 节；PromptBuilder 的长期记忆段"没开就跳过"
- **Notify 推送**：归第 19 节；**AgentScheduler 定时触发**：归第 25 节（课件分节）
- **Provider fallback / hedge racing / 认证 / 限流 / RBAC**：已在 001 明确不做，本节不翻案（需求文档 §6.1、技术方案 §7.5）
- **Tool Policy（Profile 级 allow/deny 规则）**：扩展阶段；本节不做（技术方案 §6.7 要点二）

## 验收标准

### 自动化部分（harness 承载，`mvn clean verify` 全绿即通过）

需求文档 §13 对 ReAct 项的要求："ReAct 循环（多轮 Tool 调用、正确累积消息历史、达到最大迭代次数时正确终止）"。

**本节的东西全部不碰网络**——ProviderService、工具、文件系统都能 mock 或用临时目录，所以 harness 全是单测，`mvn test` 秒级跑完（课件 §四）。真模型验证放人工部分。

**坑↔测试对号表**——功能需求中点过名的坑，每个都有一条回归测试永久钉死：

| 测试类 | 关键回归点 |
|--------|-----------|
| `ReActLoopTest` | 无 tool 调用一轮收尾；有 tool 调用 → 执行并回填进下一轮；**坑一回归**：模型一直要调工具 → 恰好 10 轮（`verify(chat, times(10))`）、返回含"达到最大轮数"；**坑三回归**：每轮响应和工具结果都累积进 Session、顺序对位；**坑六回归**（架构断言）：代码中不存在触发 Spring AI 自动 tool 执行的路径 |
| `PromptBuilderTest` | 四部分顺序正确；**坑二回归**：历史超 N 轮被截断、不切断一轮内的 tool 调用链；system prompt 末尾含当前日期时间；长期记忆段未启用时跳过（"没开就跳过"） |
| `ToolExecutorTest` | 成功写审计 `success=true`；**坑七回归**：失败也写 `success=false` 带原因、异常不吞；`retryable` 标识回传；无内部自动重试 |
| `AgentServiceTest` | 处理期间 ProfileContext 可取到当前 Profile；**坑四回归**：处理抛异常时 finally 也把它清掉；结束后 `sessionManager.save(session)` 被调用（最小契约 mock 验证） |
| `ContextLoaderTest` | **坑五回归**：改文件后下一次 build 立即读到新内容（无缓存）；显式引用缺失报错；Bootstrap 缺失至少 WARN |
| `SessionManagerTest` | 同一三元组两次 getOrCreate 返回**同一个** Session（幂等）；channel/user/profile 任一不同则不同 Session；**session_id 拼接只发生在 SessionManager 一处**（H4 不变量四） |
| `ToolInvocationRepositoryTest` | **坑八回归**：测试里执行手工 schema.sql 建表（不用 Hibernate 自动建——否则测试绿了、生产跑真脚本列名对不上白测；沿用第16节 `LlmCallRepositoryTest` 同款讲究）；`tool_invocations` 能存能读、`success`/`error_message` 两列真实存在 |

**两个最值钱的回归测试**（课件 §四原文）：

```java
@Test
void 模型一直要调工具_转满最大轮数强制停() {
    when(providerService.chat(any(), any(), any()))
        .thenReturn(responseWithToolCall(httpGetCall));   // 每轮都要调工具，永不收敛

    String reply = loop.run(session, "查天气", profileWithMaxIterations(10));

    verify(providerService, times(10)).chat(any(), any(), any());  // 恰好 10 轮，一轮不多
    assertTrue(reply.contains("达到最大轮数"));
}

@Test
void 处理中抛异常_ProfileContext也必须被清掉() {
    when(reActLoop.run(any(), any(), any())).thenThrow(new RuntimeException("boom"));

    assertThrows(RuntimeException.class, () -> agentService.process(session, "hi"));

    assertNull(ProfileContext.current());   // finally 没清，下一个复用此线程的请求会拿到别人的 Profile
}
```

第二个测试守的是最阴险的一类 bug：ThreadLocal 泄漏在单请求测试里永远不报错，只在并发复用时串号——所以必须在 harness 里显式钉死（课件 §四）。

跑法：`mvn test` 日常全跑（全绿才算实现完成）。

### 人工部分（做完怎么验）

- **Demo 一（每日天气）对话版用真模型跑通一次**：多轮对话里，Agent 调了 http_get、拿到数据、给出穿搭建议（课件 §五）。注：http_get 归第 20 节交付——本节可先用最小临时工具先行验证循环，工具就位后补跑完整 Demo，如实记录
- **人工 review**：循环是自己实现的，没用框架现成的 Agent 封装（code review 确认，测不出来）（课件 §五）
- 其余验收点——死循环兜底、累积、截断、失败审计——已由 harness 覆盖，`mvn test` 绿即打勾
- **跑通标准**：ReAct 要和上一节的 Provider 一起，才撑得起 Demo 一；这块跑通的标准很直接——Demo 一能从头到尾完整走下来（课件 §五、编程指南 §4.2）

## 依赖与假设

### 前序交付物（已就位，本节直接依赖）

- **`ProviderService.chat(sessionId, Profile, Prompt)`**（001 FR-2）：每转一圈调一次；sessionId 参数正是为 `llm_calls` 按 session 关联审计（课件 §三逐行讲解）。001"跨节契约"条：该签名改动视为修改前序公共接口，需停下报告
- **`Profile` / `ProfileLoader` / `ProfileRegistry`**（001 FR-6）：`identity.prompt`（角色设定）、`settings.max_iterations` / `max_history_turns` 一次建全
- **`Message` / `AxionTool` / `ToolResult`**（001 FR-7/FR-8）、**`ToolSchemaAdapter`**（001 FR-4）：消息形态与工具契约、Tool 列表翻译
- **`llm_calls` 审计与手工 schema.sql 口径**（001 FR-5）：本节的 `tool_invocations` 同构对称
- **工程地基**：9 模块 Maven 骨架、JDK 21 + Spring Boot、Logback 结构化 JSON、静态检查门禁——本节不重复搭地基

### 前序缺口（H0 依赖检查发现，已拍板处理方式）

- **Session 缺口（已拍板 2026-09-01）**：课件第17节以 Session 为输入，但本仓库 001 未交付 Session 类——**随本节交付最小 Session 契约**（FR-6：core 的 Session 数据结构 + SessionManager 最小契约、内存版；sessions 表与 JPA 持久化仍归第 18 节），不回改已 review 的 001
- 工具表同理：ToolRegistry 归第 20 节，本节 ToolExecutor/PromptBuilder 以注入的工具集支撑同一契约，第 20 节换成 ToolRegistry 不改 ToolExecutor

### 外部依赖与假设

- **Spring AI 边界**：LLM 调用一律走 `ProviderService`（自动执行关闭的防线在 001 已钉死）；执行权唯一——工具执行只在 `ToolExecutor` 一处
- **天气数据源**：人工验收用无需 API key 的公开天气端点（如 wttr.in），其域名白名单配置随第 23/24 节落位；运行时 `DEEPSEEK_API_KEY` / `KIMI_API_KEY` 由用户以环境变量注入（001 已约定）
- **跨节契约**：本节交付的 `ReActLoop` / `PromptBuilder` / `ToolExecutor` / `AgentService` / `ProfileContext` / `ContextLoader` / `tool_invocations` 口径，是第 18（CLI+Session）、19（Notify）、20（Tool）、21/22（Memory）、25（定时）、26（Web）节的调用契约——后续节不得改动已验收行为
- **跑通标准**：本节 + 001 撑起 Demo 一（每日天气）对话版；钟推版（`AgentScheduler` 到点触发 + `notify` 推送）归第 25 节与后续 Demo 节
