# 001-provider 代码 Review 指南

> 分支：`001-provider` | 提交：`f071452`（feat）+ `d3c6656`（chore）| PR：#1
> 生成日期：2026-08-31 | 状态：待用户 review（代码已全量测试通过、DeepSeek 冒烟已跑通）

## 一、全景：一条调用链看懂全貌

```
application.yaml (axion.providers 全局层)
        ↓ Binder 绑定
ProviderConfiguration ──→ 逐条 new OpenAiChatModel ──→ Map<String, ChatModel>
        ↓ 构造                                                ↓ 按名取
ProviderService.chat(sessionId, Profile, Prompt) ──→ ChatModel.call(关自动执行)
        ↓ 成败都走                                     ↓
   LlmCallRepository.save → llm_calls 表（SQLite）

AGENT.md frontmatter ──→ ProfileLoader.deriveProfile ──→ Profile（校验 provider 引用）
        ↓ 扫描注册
   ProfileRegistry（内存索引）
```

依赖方向：`core`（抽象）← `provider`（能力）→ `storage`（持久化），与宪法模块结构一致。

## 二、逐文件梳理

### axion-core（7 个类，纯抽象、无框架依赖）

| 文件 | 干什么 | 值得注意的点 |
|------|--------|-------------|
| `Profile.java:12` | Agent 运行时配置 record，一次建全 | 紧凑构造 + 访问器双重防御性拷贝（`Profile.java:25-31, 34-56`）；`Settings` 缺省值 10/20 在访问器兜底（`Profile.java:74-88`） |
| `ProfileLoader.java:39-98` | frontmatter → Profile，**本节唯一校验项：provider 引用必须命中全局层**（`:64-73`） | 报错信息带"已声明集合"，方便排错；`loadAll` 单目录失败记日志跳过（`:105-126`），对应"坏文件不阻断启动" |
| `ProfileLoader.java:129-154` | 手写 frontmatter 解析（`---` 分隔 + SnakeYAML） | 没有引 Jackson dataformat-yaml，少一个依赖 |
| `ProfileRegistry.java` | 内存索引，ConcurrentHashMap | 启动扫描是唯一注册路径，`register()` 是后续节预留入口 |
| `Message.java:10-43` | 对话消息 record，toolCall/toolResult 用轻量嵌套类型承载 | **core 保持框架无关**——与 Spring AI 消息格式的转换留给 provider 适配层，这是刻意边界（补充修订 2026-09-01：002-react 已拍板 core 引入 spring-ai 数据模型依赖用于 Prompt/ChatResponse/ToolCall 的构造与解析；此边界收窄为"Message 保持框架无关（Session 历史落库的序列化形态），core 禁用的只是 Spring AI 的 Agent 抽象与自动 tool 执行"） |
| `AxionTool.java` | 四方法接口 | 与 CLAUDE.md 逐字一致；`execute` 归 ToolExecutor 调度 |
| `ToolResult.java` / `JsonSchema.java` | 配套 record | 均有防御性拷贝 |

### axion-provider（5 个类，本 feature 主角）

| 文件 | 干什么 | 值得注意的点 |
|------|--------|-------------|
| `ProviderService.java:58-74` | 统一门面 `chat()`：按名路由 → 关自动执行 → 计时调用 → 成败落审计 | **失败路径先落账再原样上抛**（`:70-73`），不吞异常 |
| `ProviderService.java:80-96` | `withToolExecutionDisabled` | 宪法原则 II 的防线：无论调用方传什么 options，强制 `internalToolExecutionEnabled(false)`；调用方挂的 toolCallbacks/toolNames/toolContext 原样保留（"只翻译不执行"） |
| `ProviderService.java:99-141` | 两个审计方法 | 审计本身失败只记日志、不影响调用结果/原异常；**日志不带 sessionId/provider 参数**（防 CRLF 注入，`:117, 138` 注释说明） |
| `ProviderConfiguration.java:26-45` | Spring 装配：Binder 绑定 → 逐条 new → 显式 Map | 宪法原则 III：**无任何扫描容器 Bean 的代码**；配置缺失直接 `IllegalStateException` |
| `ProviderConfiguration.java:48-56` | 启动校验 | name/api-key 空白即报错（key 必须是 `${ENV}` 解析后的值） |
| `ToolSchemaAdapter.java:25-36` | AxionTool → `ToolDefinition` 纯数据 | 只翻译；ObjectMapper 也做了防御性拷贝 |
| `ProviderProperties.java` / `ProviderNotFoundException.java` | 配置 POJO / 异常 | 异常信息含 provider 名 |

### axion-storage（3 个类 + schema.sql）

| 文件 | 干什么 | 值得注意的点 |
|------|--------|-------------|
| `LlmCall.java` | JPA 实体 | `success`/`error_message` 两列是对 CLAUDE.md 原表结构的**补充修订**（需求文档 FR-5 有说明，失败留痕与 tool_invocations 对称）；`created_at` 走 `InstantTextConverter` 存 ISO-8601 TEXT |
| `schema.sql` | 手工建表脚本 | `ddl-auto: none` 下 Hibernate 不建表，这份脚本是唯一真相源——测试直接执行它验证（不是各写一份） |
| `LlmCallRepository.java` | 只写不查 | 核心阶段不做查询接口，符合"审计 day one 写入"的最低要求 |

### 测试（5 个类，回归点与坑一一对号）

| 测试类 | 钉死的坑 |
|--------|---------|
| `ProviderServiceTest.java:62-74` | 双 provider 路由不串台（`verify(kimi).call` + `verify(deepseek, never()).call`） |
| `ProviderServiceTest.java:87-100` | **自动执行关闭**（有人改回自动执行，测试立即红） |
| `ProviderServiceTest.java:102-120` | 失败路径"抛异常**且**审计先落账" |
| `ProviderServiceTest.java:153-175` | 架构断言：ProviderService 字段无 `List<ChatModel>` 扫描形态 |
| `ProfileLoaderTest.java:96-108` | 坏文件不阻断其余加载 |
| `LlmCallRepositoryTest.java:29-71` | **测试执行生产同一份 schema.sql**（防"测试绿了生产列名对不上"） |
| `ProviderSmokeIT.java` | integration 标签，CI 默认排除；DeepSeek 已真跑通 |

## 三、重点 review 清单（按风险排序）

1. **`ProviderService.java:80-96`** —— 全 repo 最该盯的一段。问自己：有没有任何路径能让 `internalToolExecutionEnabled` 回到 true？（答案应是：没有，builder 只在最后强制 set false）
2. **`ProviderService.java:70-73`** —— 失败路径顺序：先 `auditFailure` 再 `throw e`。如果反过来（先 throw），失败事故将无痕迹。
3. **`ProviderConfiguration.java:32-44`** —— 全 grep 一下确认没有第二处 new ChatModel 的地方；显式映射是原则 III 的命根子。
4. **`ProfileLoader.java:64-73`** —— 校验用的是 `Set.contains`，注意是"全局层已声明的名字集合"传进来的，别被误读成硬编码。
5. **`application.yaml:35-42`** —— key 占位形态 + `chat.enabled: false` 兜底（防止 Spring AI 自动装配偷跑）。

## 四、刻意留白（review 时不要当成缺陷报）

- `ProviderService` 只管单次调用——循环、上下文拼装、工具执行都不归它（NFR-3 正向边界）
- `LlmCallRepository` 无查询方法——审计"写"day one，"查"放扩展阶段
- `ProfileLoader` 只校验 provider 引用——其余字段校验归后续各节
- 无重试/降级/fallback——核心阶段直报错误（需求文档"明确不做"清单）
- `ProviderSmokeIT` 硬编码 deepseek——Kimi 冒烟是已知待办
- 建表脚本当前无人自动执行——US-2 接线（`spring.sql.init` 或启动时手动执行）是下一节任务

## 五、建议 review 顺序

按依赖方向读最快：**core 抽象（10 分钟）→ storage（5 分钟）→ provider 三件套（15 分钟）→ 测试对号（10 分钟）**。重点文件就两个：`ProviderService.java` 和 `ProviderConfiguration.java`，其余都是它们的陪衬。

## 六、当前验收状态

- 机器判卷：`mvn clean verify` 全绿（15 单测 + checkstyle/P3C-PMD/SpotBugs）
- 人工：DeepSeek 冒烟已跑通（非空响应 + 审计 success=true）
- 待办：Kimi 冒烟（无 key 跳过）、axion.db 真实落库核对（顺延 US-2 后）
