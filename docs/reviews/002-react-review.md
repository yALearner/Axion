# 002-react 代码 Review 指南

> 分支：`002-react` | 提交：`ccd7fdd`（feat）+ `bb80bf2`（fix）| PR：#2
> 生成日期：2026-09-02 | 状态：待用户 review（代码已全量测试通过、Demo 一人工验收已通过）

## 一、全景：一条消息的生命周期看懂全貌

```
AgentService.process(session, userMessage)          ← 三触发源唯一入口（宪法 VIII）
  ├─ ProfileRegistry.findByName → Profile
  ├─ ProfileContext.set(profile)                    ← 工具执行靠它知道"当前是哪个 Agent"
  └─ ReActLoop.run（七步循环，宪法 I 自实现）
       ├─ session.append(user)                      ← 坑三：每轮先累积再继续
       ├─ PromptBuilder.build(session, profile)     ← 四段组装
       │    ├─ ① system = identity.prompt + ContextLoader.load(profile) + 当前日期时间
       │    │        └─ bootstrap 文件 + skills 软连接元数据（每轮现读，坑五）
       │    ├─ ② 长期记忆：未就绪跳过（第 21/22 节拼接位）
       │    ├─ ③ 历史：最近 N 轮（坑二截断，不切 tool 链）
       │    └─ ④ 工具：Profile.tools 过滤 → ToolSchemaAdapter → ToolCallbacks
       ├─ LlmGateway.chat(session.id, profile, prompt)   ← 端口接口（G2 拍板）
       │    └─ ProviderService（001）：路由 + 关自动执行 + llm_calls 落库
       ├─ 无工具调用 → 返回最终答复
       ├─ 有 → ToolExecutor.execute(session.id, call)    ← 执行权唯一（宪法 II）
       │        └─ AxionTool 执行 → tool_invocations 落库（成败都写，宪法 V）
       └─ 转满 maxIterations(10) → "达到最大轮数，已停止"（坑一）
  └─ finally: ProfileContext.clear()                ← 坑四：异常也清
```

依赖方向：`core`（引擎+抽象）← `provider`（能力）→ `storage`（持久化）；`LlmGateway` 是 core 唯一对 provider 的倒置接缝；`ToolExecutor` 不持 Sandbox 引用（core 不反向依赖 axion-tool）。

## 二、逐文件梳理

### axion-core（引擎本体，9 个新类 + 1 个迁入）

| 文件 | 干什么 | 值得注意的点 |
|------|--------|-------------|
| `ReActLoop.java:37-59` | 七步主循环（96 行，宪法 I 自实现） | 停止条件只有两个：无工具调用（`:50-52`）或转满轮数（`:58`）；每轮 LLM 响应**先累积再判断**（`:45`），工具结果逐条回填（`:54`） |
| `ReActLoop.java:62-71` | ChatResponse → core Message 私有转换 | Session 历史保持框架无关（可 JSON 序列化，第 18 节落库）；转换是本类私有职责、唯一调用方 |
| `ReActLoop.java:74-83` | 工具结果 → TOOL 消息 | 失败时带"（可重试）/（不建议重试）"提示回传（`:79`）——重试是 LLM 下一轮的决定 |
| `PromptBuilder.java:56-78` | 四段组装（技术方案 §4.2 逐字） | 长期记忆段是**留白注释**不是代码（`:64`）；工具段为空直接返回无 options 的 Prompt（`:71-72`） |
| `PromptBuilder.java:81-95` | ToolDefinition → ToolCallback | 回调体"执行即炸"（`:84`）——若框架误调立即抛异常，坑六的第二道防线 |
| `PromptBuilder.java:102-117` | 按 `Profile.tools` 过滤注入工具集 | 未知工具名 WARN 跳过（校验归第 20 节 ToolRegistry） |
| `PromptBuilder.java:124-135` | **坑二截断语义** | 从尾部回数 USER 消息算"轮"，TOOL 消息跟随所属轮成组保留——绝不拦腰切断 tool 链 |
| `PromptBuilder.java:139-165` | core Message → Spring AI 消息 | TOOL 回填 name 以空串占位（core 契约只存 id+内容，001 已定）——review 时别当缺陷 |
| `ToolExecutor.java:39-73` | 执行权唯一入口 | 三路径都写审计：未知工具（`:44-53`）、工具返回失败（`:62-64`）、工具抛异常（`:66-72`）；异常不吞、以失败结果回传 |
| `ToolExecutor.java:87-115` | 审计落账 + 结构化日志 | 审计本身失败只记日志（`:95-98`）；**日志参数不含 sessionId/toolName**（防 CRLF 注入，001 先例） |
| `ContextLoader.java:37-42` | load = bootstrap + skill 元数据 | 每次现读、无任何缓存字段（坑五①） |
| `ContextLoader.java:44-63` | Bootstrap 读取 | 显式引用缺失 `IllegalStateException`（`:53`）；bootstrap 列表为空 WARN（`:47`）——两条铁律（坑五②） |
| `ContextLoader.java:66-84, 113-131` | Skill 绑定解析 | 绑定真实性经 `toRealPath` 后必须位于 `.axion/skills/` 根内，逃逸即报错（`:127-129`）；只注入 name/description/读取路径，正文不预载（宪法 IV） |
| `ContextLoader.java:138-148` | 绑定判定 | symlink 或 Windows junction（`isOther()`）——本机无符号链接特权时 junction 是等价落法 |
| `AgentService.java:25-37` | 编排者（宪法 VIII） | 顺序：set → run → save → **finally clear**（`:36`）；Profile 未注册清晰报错（`:28-29`） |
| `ProfileContext.java:14` | ThreadLocal\<Profile> | 只存不删的设计在并发复用下串号——清理责任全在 AgentService 的 finally |
| `Session.java:32` / `SessionManager.java:20-38` | 会话最小契约 | `sessionIdOf`（`:37-38`）是**全仓库唯一**的 session_id 拼接点（H4 不变量四，包私有构造器挡住第二条生成路径）；save 是 no-op 占位（第 18 节 JPA 化） |
| `LlmGateway.java` | 依赖倒置端口（G2 拍板） | 签名与 `ProviderService.chat` 逐字一致；行为不变量由 001 契约保证 |

### 改造点（G2 拍板，跨 001 文件——单独 review）

| 文件 | 改动 | 注意 |
|------|------|------|
| `axion-provider/ProviderService.java` | +`implements LlmGateway` +`@Override`（各 1 行） | 方法体零改动；001 全部测试原样绿 |
| `ToolSchemaAdapter`（provider → core） | 类 + 测试随迁，包名改 `com.axion.core` | 逻辑零改动（grep 对比过）；provider 侧生产代码无引用 |
| `AxionApplication.java:26-28`（fix `bb80bf2`） | +`@EnableJpaRepositories`/`@EntityScan` | `scanBasePackages` 不作用于 JPA 扫描——人工验收实机暴露的启动缺口 |

### axion-tool（首份内容，纯接口墙）

| 文件 | 干什么 | 值得注意的点 |
|------|--------|-------------|
| `Sandbox.java` | 单方法 `enforce(SandboxAction)` | 签名中立：不出现白名单/容器/VM 字样（宪法 VI）；违规审计复用 ToolExecutor 既有失败路径 |
| `SandboxAction.java` / `ActionType.java` | record + 四值枚举 | 字面量照技术方案 §6.7：FILE_READ/FILE_WRITE/SHELL_COMMAND/HTTP_REQUEST |
| `SandboxViolationException.java` | RuntimeException | 信息说明被拒动作 |

### axion-storage（审计扩展）

| 文件 | 干什么 | 值得注意的点 |
|------|--------|-------------|
| `ToolInvocation.java` | JPA 实体 | 与 001 `LlmCall` 同口径：`success`/`error_message` 两列真实存在、`created_at` 走 `InstantTextConverter` 存 ISO-8601 TEXT |
| `schema.sql` | **增量追加** tool_invocations DDL | 手工脚本是唯一真相源（ddl-auto: none）；测试执行同一份脚本（坑八） |
| `ToolInvocationRepository.java` | 只写不查 | 审计"写"day one、"查"归扩展阶段 |

### 测试（9 个类，坑↔测试对号表逐条钉死）

| 测试类 | 钉死的坑（需求文档对号表） |
|--------|--------------------------|
| `ReActLoopTest` | 坑一：恰好 10 轮 `verify(chat, times(10))` + "达到最大轮数"；坑三：4 条消息按序对位；坑六：工具经 ToolExecutor 恰好一次；纠偏回归：失败结果含"（可重试）"进 TOOL 消息 |
| `PromptBuilderTest` | 坑二：21 轮 → 20 轮、被切轮的 tool 链整体丢弃、保留轮的 tool 链完整；四部分顺序 + 日期在 system 末尾 + 空工具集不报错 |
| `ToolExecutorTest` | 坑七：成败都落审计、retryable 原样回传、异常不吞、`events=["execute","audit"]` 证明"先落账后返回"、无内部自动重试（times(1)） |
| `AgentServiceTest` | 坑四：处理抛异常 finally 也清 ProfileContext（`assertNull`） |
| `ContextLoaderTest` | 坑五：改文件立即生效、显式缺失报错、Bootstrap 缺失 WARN（logback ListAppender 实测）、skill 逃逸公共根报错 |
| `SessionManagerTest` | H4 不变量四：三元组幂等 + 架构断言"Session 无公开构造器" |
| `ToolInvocationRepositoryTest` | 坑八：测试执行生产同一份 schema.sql；`success`/`error_message` 列真实存在 |
| `ToolSchemaAdapterTest`（随迁） | 001 原样绿，证明迁移零改动 |
| `ProviderServiceTest`（001） | implements LlmGateway 后原样绿，证明契约零破坏 |

## 三、重点 review 清单（按风险排序）

1. **`PromptBuilder.java:81-95`（宪法 II 第二道防线）**——ToolCallback 回调体必须永远不可达。问自己：有没有任何路径会让 `neverCalled` 这个 lambda 被 Spring AI 调用到？（答案：没有——自动执行在 001 已强制关闭，执行只经 ToolExecutor；即便框架误调，抛出的异常会立即暴露）
2. **`ReActLoop.java:37-59`（宪法 I 命根子）**——停止条件只有两个、工具执行只有一条路。grep 全 repo 确认没有第二处 `AxionTool.execute` 调用方，没有 `ChatClient`/`ToolCallingManager` 出现。
3. **`ToolExecutor.java:39-73`（宪法 V）**——三个出口（未知工具/工具失败/工具抛异常）是否都写了审计？失败路径是否"先落账再返回"？日志参数是否真的不含 sessionId/toolName（CRLF 注入）？
4. **`PromptBuilder.java:124-135`（坑二语义）**——截断边界：一轮 = USER 起；TOOL 消息绝不能脱离所属轮单独保留。构造一个"第 N+1 轮的 tool 消息出现在第 N 轮尾部"的刁钻历史验证。
5. **`AgentService.java:25-37`（坑四）**——finally 是否覆盖所有 return/throw 路径？`ProfileContext.clear()` 用的是 `remove()` 还是 `set(null)`（应为 remove，防内存泄漏）？
6. **`ContextLoader.java:113-131`（宪法 IV 安全边界）**——`toRealPath` + `startsWith(公共 Skill 根)` 是否真的防住了 `..` 逃逸？（junction/symlink 都先解析再比对；注意 startsWith 用的是解析后的真实路径）
7. **`AxionApplication.java:26-28`（fix 提交）**——`@EnableJpaRepositories`/`@EntityScan` 显式声明后，是否还有重复扫描或遗漏 `com.axion.storage` 之外未来仓储的风险（后续模块的 Repository 也要进这两个注解的 basePackages）。

## 四、刻意留白（review 时不要当成缺陷报）

- `SessionManager.save` 是 no-op——sessions 表与 JPA 持久化归第 18 节，契约先行
- PromptBuilder 长期记忆段是注释不是代码——Memory 模块归第 21/22 节，"没开就跳过"
- TOOL 消息回填 name 为空串——core 契约（001 已定）只存 toolCallId+内容
- Sandbox 无人调用、无实现——接口墙先行（FR-7），接线归第 20 节、白名单归第 23/24 节
- ToolExecutor 工具集是 `Map<String, AxionTool>` 注入——第 20 节换成 ToolRegistry 不改本类
- 无工具并行、无自动重试、无流式、无 fallback——需求文档"明确不做"清单
- 本节无 CLI/Web 装配——触发源接入从第 18 节开始，编排者本体已就位
- Windows junction 与 symlink 并列作为绑定形态——本机无符号链接特权下的等价落法（测试注释有说明）

## 五、建议 review 顺序

按一条消息的生命周期读最快：**ReActLoop（15 分钟）→ PromptBuilder（15 分钟）→ ToolExecutor（10 分钟）→ AgentService + ProfileContext（5 分钟）→ Session/SessionManager（5 分钟）→ ContextLoader（10 分钟）→ 改造点三处 + Sandbox 接口墙（10 分钟）→ 测试对号表抽查（15 分钟）**。

重点文件三个：`ReActLoop.java`、`PromptBuilder.java`、`ToolExecutor.java`——坑一/二/三/六/七都在它们身上。改造点看 diff 而非全文（G2 拍板过，只核对"零改动"是否属实）。

## 六、当前验收状态

- 机器判卷：`mvn clean verify` 全绿（47 tests + spotless/errorprone -Werror/SpotBugs/FindSecBugs/P3C）
- 人工：Demo 一（每日天气）真模型跑通——3 轮思考 + 2 次 http_get + 中文穿搭建议；llm_calls=3 / tool_invocations=2 真实落库
- 遗留：第 20 节 HttpTools 就位后补跑完整 Demo（临时工具替身退役）
