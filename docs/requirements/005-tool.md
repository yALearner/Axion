# Tool 体系模块设计文档

> 需求编号：005-tool | 对应主体阶段 US-4（Plugin Tool，核心能力四；课件第 20 节：Tool 体系）
> 文档依据：`docs/AiProgrammingGuide.md` §4.4、`docs/TechnicalSolution.md` §6（§6.1~§6.6）、`docs/DemandAnalysis.md` §5.6/§13、`docs/IndustryResearch.md` §5.6/附录 A；课件《第 20 节：Tool 体系 原理解析、实现与代码讲解》（course repo `D:\code\axion\docs\class\`，实施级事实源）
>
> 修订说明（2026-09-05）：本版对齐课件第 20 节——① 课件骨架的 `@Tool` 注解形态**机械适配为 Axion 的 `AxionTool` 纯实现类**（004-notify 已拍板同一方向：G4-C1 钉死 boot 全树扫描风险，内置 Tool 手写 JsonSchema；方式三业务 Bean 的 `@Tool` 方法经包装器收编进 AxionTool，执行仍走 ToolExecutor——宪法 II 只借 Spring AI 的 schema 生成与扫描，不借自动执行）；② **Sandbox 无实现期间的生产装配策略经用户拍板（2026-09-05）**：装配处挂**临时全放行 `PermissiveSandbox` @Bean**（javadoc 标注第 24 节替换为 `WhitelistSandbox`），Demo 一对话版 20 节即可真跑；风险口径：20~23 节白名单未生效，靠内网假设 + `tool_invocations` 审计留痕兜底；③ MCP 客户端 API 形态：课件 `McpClient.connect` 为示意——本地仓库实测 `spring-ai-mcp` 1.1.8 仅协议壳（无 client 类），需新增 `spring-ai-starter-mcp-client` 依赖并以其实测 API 为准（实施时 H3 核实）；④ 编程指南 §4.4 把"SandboxChecker 完整版"列入 US-4 task 拆解，与 002 FR-7 拍板（WhitelistSandbox 归第 23/24 节）冲突 → **按 002 拍板**（本节不翻案，Sandbox 本体明确不做）。
>
> 修订说明（2026-09-05 设计期自审，用户拍板修入）：⑤ 安全窗口纪律——PermissiveSandbox 全放行期间（20~23 节）工具注册面扩大 6 倍，补"保守 Profile 纪律"（不建议 shell/http_post 进任何 Agent 的 tools 声明，24 节替换后解除）；⑥ 补 004 遗留补验（chat 里 LLM 自动调 notify 端到端）进本节人工验收；⑦ 方式三（FR-6）补执行契约与骨架 + H3 核实降级策略；⑧ 补注册/过滤语义（重名拒绝 + WARN、未知工具名启动报错）、内置工具参数规格表、FileTools 三实现类形态、ShellTools 平台假设、AnnotatedMethodToolAdapterTest、MCP 同步门面 Reactor 口径。

## 背景与价值

一句话：**Provider 让 Agent 会调模型，ReAct 让它会思考，CLI 给了入口，Notify 补了出口——Tool 是让 Agent 真正动手干事的那双手**（课件 §一：LLM 负责想，Tool 负责真的去读文件、跑命令、调接口）。

大模型本身只会生成文字：它读不了磁盘文件、发不出 HTTP 请求。想让 Agent 干活，就得给它一批能操作外部世界的工具——LLM 通过 Function Calling 决定"调哪个工具、传什么参数"（001 已交付协议翻译），Axion 负责把工具真正执行掉、把结果递回给 LLM（ReAct 循环里 "Act" 那一步，002 已交付调度与审计）（课件 §一、技术方案 §6）。

本课把前序积木拼成"动手能力"：`AxionTool` 抽象与 `ToolResult`（001 交付）是地基，`ToolExecutor` 的按名调度与审计（002 交付）是执行点，`Sandbox` 接口墙（002 交付）是安全校验入口——本课交付的是**把工具真正造出来并注册进执行链路**的那层：内置 Tool 六个（FileTools×3、ShellTools、HttpTools×2）、MCP 客户端接入（方式二）、`ToolRegistry` 统一注册与按 Profile 过滤、以及 004 的 `NotifyTools` 正式接线（课件 §二/§三、技术方案 §6.2/§6.4/§6.6）。

安全是本课的隐形主线：工具能读文件、跑命令、发请求，一旦被乱调就是事故。核心阶段唯一的治理手段是执行前统一过白名单校验——校验本体归 23/24 节 Sandbox，本课把**"execute 首行先 enforce、通过才 IO"**这条硬规矩先立起来（课件 §二第三、技术方案 §6.7、002 contracts/sandbox.md 行为不变量三）。同时按拍板挂临时放行 bean 保证 Demo 一对话版本节即可真跑，24 节无缝替换（修订说明 ②）。

## 用户场景

**场景一（本节验收场景）：chat 里 Agent 真的去查了天气**——Demo 一对话版补跑：用户在 `axion chat` 里问"北京今天天气怎么样"，Agent 调 `http_get` 真发请求拿回数据，再生成穿搭建议。到这一步，配合 Provider、ReAct、CLI，Demo 一的对话版闭环（课件 §五；`tool list` 可见全部注册工具）。

**场景二：业务方零代码扩展（方式一，主推）**——业务方写一个 Agent 目录（`AGENT.md` 正文描述任务）+ 在 `mcp_servers.yaml` 里配置复用的社区 MCP server，一行代码不写。本课交付方式二的 MCP Client 地基（连接、`tools/list`、包装注册、转发执行），方式一的完整验证依赖真模型与真 server（31 节日报 Agent 硬依赖，课件 §二第二/§四）。

**场景三：越界会被拦**——Agent 想读白名单外的文件、跑白名单外的命令、请求白名单外的域名：`execute` 首行 `sandbox.enforce(...)` 先校验，不过直接抛 `SandboxViolationException`，请求根本发不出去（本课以 mock 验证拦截链路；三层白名单实现归 23/24 节）（课件 §二第三/§四、002 contracts/sandbox.md）。

**场景四：每个 Agent 只拿到声明的工具**——Profile 的 `tools` 字段限定子集：`ToolRegistry` 按它过滤，"不多一个、少一个都是错"。这是核心阶段 Tool 治理的雏形，完整的 allow/deny 策略扩展阶段补（课件 §三/§四、技术方案 §6.6/§6.7 要点二）。

**场景五：MCP server 失联不拖垮底座**——配置里的某个 MCP server 挂了：启动时只 WARN 跳过它的工具，其余工具照常注册，Axion 照常起（课件 §三最值钱测试之一）。

## 功能需求

> 从课件一、二、三部分提炼：编程指南 §4.4（US-4 任务大类）、技术方案 §6.1~§6.6、需求文档 §5.6/§13。**交付物列是本节对外概念的白名单**，清单之外的新增对外概念必须停下报告。

| 编号 | 需求 | 交付物（落位模块） | 来源 |
|------|------|-------------------|------|
| FR-1 | **`ToolRegistry` 统一注册与按 Profile 过滤**：三种来源的工具（内置、方式二 MCP、方式三 `@Tool` 包装）统一包装成 `AxionTool` 实例注册，ReAct 循环由此对来源无感知；按 Profile 的 `tools` 字段过滤出该 Agent 可用子集，**"不多不少"**（多一个 = 没过滤干净、少一个 = 过滤过头，都是错——坑十四）；提供注册、按名取、是否存在、全量列表（`tool list` 命令与 26 节 `/tools` 端点共用）。**注册与过滤语义（2026-09-05 自审补钉）**：重名注册明确拒绝 + WARN 日志（不静默覆盖——防 MCP 工具意外遮蔽内置工具）；Profile 声明了 Registry 不存在的工具名 → 启动校验明确报错（001 provider 引用校验同款纪律，不静默少一个） | `ToolRegistry`（axion-tool） | 课件 §二第一/§三；技术方案 §6.6；001 FR-1 校验纪律 |
| FR-2 | **`FileTools` 三件（read_file/write_file/list_dir）**：implements `AxionTool`（004 机械适配先例：`@Tool` 注解骨架 → 纯实现类 + 手写 JsonSchema，G4-C1 不加组件注解）；`execute` **首行** `sandbox.enforce(new SandboxAction(FILE_READ/FILE_WRITE, path))`（坑十延续：enforce 先于 IO，顺序反了就是漏洞），通过才真正读写；读写路径以参数传入、不得硬编码；写文件覆盖语义明确（write_file 覆盖已存在文件，目录不存在时明确报错——实现级明确）。**形态（实现级明确）**：三个独立实现类 `ReadFileTool`/`WriteFileTool`/`ListDirTool` 落 builtin 子包（一个类只能实现一个 `getName()`；交付物统称沿用课件 FileTools 三件） | `ReadFileTool`/`WriteFileTool`/`ListDirTool`（axion-tool builtin 子包，类名实现级明确） | 课件 §二第三/§三；技术方案 §6.2/§6.7；002 contracts/sandbox.md |
| FR-3 | **`ShellTools`（shell）**：执行 bash 命令，**带超时**（实现级明确：构造注入 timeoutMs、装配处默认 30_000——可测性要求，`ProcessBuilder` + `waitFor(timeoutMs)`，超时强制销毁进程并明确报错）；`execute` **首行** `sandbox.enforce(new SandboxAction(SHELL_COMMAND, 命令))`；命令白名单规则本体归 23/24 节（本节只立"先校验后执行"） | `ShellTools`（axion-tool，builtin 子包） | 技术方案 §6.2（带超时与命令白名单）；需求文档 §5.6 |
| FR-4 | **`HttpTools` 两件（http_get/http_post）**：用 `RestClient` 发请求（004 先例 + 契约不变量 9 的 builder 产出）；`execute` **首行** `sandbox.enforce(new SandboxAction(HTTP_REQUEST, url))`；GET 返回响应体文本、POST 支持 JSON body；响应体大小上限（实现级明确：如 1MB，防超长响应撑爆上下文） | `HttpTools`（axion-tool，builtin 子包） | 课件 §三（http_get 骨架）；技术方案 §6.2；004 contracts 不变量 9 |
| FR-5 | **MCP 方式二：`McpClientService` + `McpToolAdapter`**：启动时读 `.axion/mcp_servers.yaml`（SnakeYAML，003 依赖先例）声明 `name`/`transport`/`command`/`env`；逐个连接、调 `tools/list`、每个 MCP 工具包装成 `AxionTool` 注册进 `ToolRegistry`；**失联只 WARN 跳过、其余照常注册、启动不炸（坑十三）**——外部依赖的可用性不是自己的可用性；执行时 JSON-RPC 转发、结果包 `ToolResult`（失败 `retryable=true`）；核心阶段只做 stdio transport（编程指南 §4.4：SSE 放扩展） | `McpClientService`、`McpToolAdapter`、`McpServerConfig`（yaml 解析 record，实现级明确）（axion-tool） | 课件 §三；技术方案 §6.4；编程指南 §4.4 |
| FR-6 | **方式三：`@Tool` 注解 Bean 的扫描包装**：启动时扫描容器内 `@Tool` 注解方法，仅借 Spring AI 做 schema 生成与注册发现（宪法 II 允许的两件事），包装成 `AxionTool` 注册进 `ToolRegistry`；**执行仍走 `ToolExecutor` 链路**——包装器的 `execute` 内调方法、结果包 `ToolResult`，不得启用 Spring AI 自动执行（坑二延续：自动执行 = tool 被调两次）。**执行契约（2026-09-05 自审补钉）**：方法返回值序列化为文本包 `ToolResult.success`，方法抛异常 → 上抛由 ToolExecutor 审计；扫描 API 形态实施前 H3 核实（Spring AI 1.1.8 实测），核实不到 → **降级为装配处手动注册并在 flow-status 记录**，不静默硬接 | `AnnotatedMethodToolAdapter`（axion-tool，包装器类名为实现级明确——课件点名行为未点名类名） | 课件 §三（方式三）/§五；技术方案 §6.5；宪法 II |
| FR-7 | **004 遗留接线与装配改造**：改造 `CliAgentConfiguration`（003 交付，现注入空 Map）：工具集换成 `ToolRegistry`（内置六个 + 方式三包装 + `NotifyTools` + MCP 注册全汇入）；按 004 契约不变量 9 构建 `RestClient`（Boot 自动配置 `RestClient.Builder` + connect/read timeout）注入 `WebhookNotifyAdapter`，装配 `Map.of("webhook", webhookAdapter)` + `NotifyChannelRegistry`（真实 `NotifyChannelRepository`）显式 `@Bean` `NotifyTools`；**临时 `PermissiveSandbox` @Bean（拍板方案 A）**：全放行实现 `Sandbox`，javadoc 标注"第 24 节替换为 `WhitelistSandbox`"——Demo 一对话版本节真跑，20~23 节白名单未生效以内网假设 + 审计留痕兜底。**安全窗口纪律（2026-09-05 自审补钉）**：PermissiveSandbox 全放行期间（20~23 节）**不建议 `shell`/`http_post` 进任何 Agent 的 `tools` 声明**（保守 Profile——工具注册面扩大 × 白名单缺失的窗口期，prompt injection 风险最大）；24 节 WhitelistSandbox 替换后解除 | `CliAgentConfiguration` 改造 + `PermissiveSandbox`（axion-cli 装配处 / axion-tool，拍板） | 003 FR-10；004 FR-7/契约不变量 9；修订说明 ②⑤ |
| FR-8 | **工具契约三件套（坑十二）**：任何注册进 `ToolRegistry` 的工具 name/description/inputSchema 都非空——`getInputSchema()` 漏实现会让 Provider 翻译 Function Calling 时直接卡死（课件"动手前先检查"的自动化版），由 `AxionToolContractTest` 参数化遍历 Registry 钉死 | `AxionToolContractTest`（axion-tool 测试） | 课件 §二第一/§四最值钱测试 |
| NFR-1 | 全程同步阻塞，不引入异步模型；并发由 Java 21 虚拟线程承担；MCP 连接与调用同步阻塞 | — | 宪法 VII；002 NFR-1 延续 |
| NFR-2 | 审计 day one：所有工具执行成败都落 `tool_invocations`——复用 `ToolExecutor` 既有路径（本课**不新增审计逻辑、不改 ToolExecutor**，宪法 V） | — | 宪法 V；004 NFR-3 延续 |
| NFR-3 | 结构化 JSON 日志沿用既有地基；MCP 失联 WARN 带 server 名；工具入参（URL/路径/命令）不进日志参数（004 NFR-2 口径延续） | — | 004 NFR-2；课件 §三（WARN 跳过） |

![Tool 体系全链路：ToolRegistry 三来源统一注册（内置六件/方式二 MCP 包装/方式三 @Tool 包装 + NotifyTools 004 接线）→ Profile.tools 过滤不多不少 → ToolExecutor 按名调度（审计 day one 双轨）→ 各 Tool execute 首行 Sandbox.enforce 先于 IO（坑十）→ 真 IO；MCP 失联只 WARN 不拖垮启动（坑十三）；PermissiveSandbox 临时放行 24 节替换（拍板）；契约三件套参数化兜底（坑十二）](../../website/public/images/docs-tool-system-flow.svg)

### 内置工具参数规格（2026-09-05 自审补钉——LLM 靠 schema 正确传参，参数设计即工具可用性）

| 工具 | 入参（* 必填） | 出参（ToolResult.content） | 行为语义 |
|------|---------------|--------------------------|---------|
| `read_file` | `path`*（文件路径） | 文件内容文本 | 不存在/不可读 → 明确报错 |
| `write_file` | `path`*、`content`* | 确认信息（如"已写入 N 字节"） | 覆盖已存在文件；父目录不存在 → 明确报错，不递归建目录 |
| `list_dir` | `path`*（目录路径） | 条目列表（名 + 类型） | 非目录 → 明确报错 |
| `shell` | `command`*（bash 命令） | 标准输出文本 | 超时（实现级默认 30s）强制销毁进程 + 明确报错；退出码非 0 → `ToolResult.failure`（stdout/stderr 进 errorMessage） |
| `http_get` | `url`* | 响应体文本 | 上限 1MB（超限明确报错）；4xx/5xx 异常上抛（坑十一口径） |
| `http_post` | `url`*、`body`（JSON 字符串）、`contentType`（默认 application/json） | 响应体文本 | 同 http_get；form/文件上传明确不做 |

### 核心代码骨架（与课件第 20 节一致，@Tool 形态机械适配为 AxionTool，包名机械适配 com.axion）

```java
// axion-tool：com.axion.tool —— 注册与过滤（课件 §三逐字适配）
public class ToolRegistry {

    private final Map<String, AxionTool> tools = new ConcurrentHashMap<>();

    public void register(AxionTool tool) { /* 三种来源统一入口 */ }
    public boolean contains(String name) { /* tool list / 失联测试用 */ }
    public List<AxionTool> all() { /* 全量列表 */ }

    public List<AxionTool> filter(Profile profile) {
        // 坑十四：按 profile.tools() 过滤——子集恰好等于声明列表，不多不少
    }
}
```

```java
// axion-tool：com.axion.tool.builtin —— 内置 Tool（课件 §三骨架，@Tool → AxionTool 机械适配，004 先例）
public class HttpTools implements AxionTool {       // http_get / http_post 两件（或两个内部实现类，实现级明确）

    private final Sandbox sandbox;
    private final RestClient restClient;            // 004 契约不变量 9 的 builder 产出

    @Override
    public ToolResult execute(JsonNode input) {
        String url = input.get("url").asText();
        sandbox.enforce(new SandboxAction(ActionType.HTTP_REQUEST, url));  // 坑十：enforce 先于 IO——顺序反了就是漏洞
        return ToolResult.success(restClient.get().uri(URI.create(url)).retrieve().body(String.class));
    }
}
```

```java
// axion-tool：com.axion.tool.mcp —— MCP 方式二（课件 §三骨架，McpClient 示意 API 以 1.1.8 实为准，实施 H3 核实）
public class McpClientService {

    private final ToolRegistry toolRegistry;

    @PostConstruct
    public void connectAll() {
        for (McpServerConfig cfg : loadConfigs()) {          // 读 .axion/mcp_servers.yaml（name/transport/command/env）
            try {
                // 连接（stdio）、tools/list、逐个 new McpToolAdapter(...) 包装注册
            } catch (Exception e) {
                LOG.warn("MCP server {} 连接失败，跳过它的工具", cfg.name(), e);   // 坑十三：失联不拖垮启动
            }
        }
    }
}

public class McpToolAdapter implements AxionTool {
    // getName/getDescription/getInputSchema 直接映射 tools/list 返回
    @Override
    public ToolResult execute(JsonNode input) {
        return client.callTool(spec.name(), input)      // JSON-RPC 转发
                .map(ToolResult::success)
                .orElseGet(() -> ToolResult.failure("MCP 调用失败", true));   // 可重试
    }
}
```

```java
// axion-tool：com.axion.tool —— 方式三包装器（课件 §三，扫描 API 实施前 H3 核实；核实不到降级手动注册）
public class AnnotatedMethodToolAdapter implements AxionTool {

    // 包装一个 @Tool 注解方法（schema 经 Spring AI 生成翻译，执行不借 Spring AI——坑二）
    @Override
    public ToolResult execute(JsonNode input) {
        return ToolResult.success(callback.call(input.toString()));   // 方法返回值序列化为文本
        // 方法抛异常 → 原样上抛，由 ToolExecutor 审计（坑十一口径）
    }
}
```

```java
// axion-tool：com.axion.tool —— 临时放行（拍板方案 A，javadoc 必须标注替换时机）
/** 临时全放行 Sandbox——第 24 节替换为 WhitelistSandbox（002 FR-7）。仅用于 20~23 节生产接线。 */
public class PermissiveSandbox implements Sandbox {
    @Override
    public void enforce(SandboxAction action) { /* 全放行；替换后由三层白名单接管 */ }
}
```

### 本节交付物清单（Spec-Kit 拆解锚点 / axion-spec 交付清单比对基准）

- **代码**：`ToolRegistry`、`FileTools` 三件（`ReadFileTool`/`WriteFileTool`/`ListDirTool`，类名实现级明确）、`ShellTools`、`HttpTools`（http_get/http_post）、`McpClientService`、`McpToolAdapter`、`McpServerConfig`（yaml 解析 record）、`AnnotatedMethodToolAdapter`（方式三包装器）、`PermissiveSandbox`（临时放行，24 节替换）——以上落 axion-tool（builtin 子包放内置 Tool、mcp 子包放 MCP 两件，实现级明确）；`CliAgentConfiguration` 改造 + `ToolListCommand` 改造（axion-cli，003 交付物改造：tool list 自占位轻命令改为重命令，读 ToolRegistry 全量列表——2026-09-05 收尾自审补钉）
- **测试**：`AxionToolContractTest`（坑十二参数化）、`ToolRegistryTest`（坑十四不多不少 + 重名注册拒绝 + 未知名启动报错）、`FileToolsTest`/`ShellToolsTest`/`HttpToolsTest`（各"正常跑通 + 越界被拦"两条，mock Sandbox）、`McpClientServiceTest`/`McpToolAdapterTest`（坑十三失联隔离 + 转发原样 + 结果包装）、`AnnotatedMethodToolAdapterTest`（方式三 execute 转发调方法 + 结果包装 + 无自动执行断言，2026-09-05 自审补钉）；004 的 4 个测试类全量回归绿
- **依赖**：axion-tool pom 增加 `spring-ai-starter-mcp-client`（版本随 spring-ai-bom 1.1.8；本地仓库实测 spring-ai-mcp 仅协议壳无 client 类，实施时 `mvn dependency:resolve` + jar 反查核实 API）+ `spring-ai-autoconfigure-model-tool`（方式三 @Tool 扫描的 MethodToolCallbackProvider bean 自动配置来源；2026-09-05 用户拍板补列——实跑发现降级状态后闭环）
- **表**：无新表（MCP 配置走 `.axion/mcp_servers.yaml` 文件；审计复用 `tool_invocations`）
- **约定**：坑十（enforce 先于 IO）延续到 File/Shell/Http 三件；坑十二（契约三件套）、坑十三（MCP 失联隔离）、坑十四（过滤不多不少）为本课新立；重名注册拒绝 + WARN、Profile 未知工具名启动报错（FR-1 自审补钉）；**安全窗口纪律**：20~23 节不建议 shell/http_post 进任何 Agent 的 tools 声明（FR-7 自审补钉）；`PermissiveSandbox` 24 节替换标注；G4-C1 组件注解纪律延续（FileTools 等纯类交付，装配处显式 `@Bean`；McpClientService 依赖全部就位可 `@Component`）；`@Tool` 仅用于方式三扫描与 schema 生成，自动执行禁用（坑二延续）

### 配置形态示例

```yaml
# .axion/mcp_servers.yaml（003 init 已建模板，本节消费；name/transport/command/env，stdio 起步）
mcp_servers:
  - name: github-mcp
    transport: stdio
    command: npx
    env:
      GITHUB_TOKEN: ${GITHUB_TOKEN}        # 凭证走环境变量占位，不明文（001 口径延续）
```

## 明确不做

> 来源：课件 §三"有几样先别做"、编程指南 §4.4 拆解、技术方案 §6.7/§6.8、需求文档 §5.6/§6.x、002/003/004 前序拍板。

- **`WhitelistSandbox` 三层白名单实现与 `file.allowed_paths`/`shell.allowed_commands`/`http.allowed_domains` 配置键**：归第 23/24 节（002 FR-7 已定；编程指南 §4.4 的"SandboxChecker 完整版"旧拆解按 002 拍板修正——本节不翻案）；白名单拦截的人工反例验证同归 24 节
- **`save_memory`/`recall_memory`（MemoryTools）**：归第 21/22 节 Memory（技术方案 §6.2 九件里的 Memory 两件）
- **Tool Policy（allow/deny 规则）、按需加载、把 Axion 暴露为 MCP server、容器级沙箱、一次响应多工具并行调用**：扩展阶段（课件 §三、技术方案 §6.7 要点二）
- **MCP SSE transport**：放扩展阶段（编程指南 §4.4：先实现 stdio）
- **方式一（Skill 加载）**：`ContextLoader` 归 core（002 已交付），Agent 目录不是 Tool（宪法 IV/IX），本节不翻案
- **`init` 命令 mcp_servers.yaml 模板示例化**：003 已建一行注释模板，本节只消费不改（配置示例以本文档为准）
- **`tool list` 的 Web 端点 `/api/v1/tools`**：归第 26 节 Web Service（CLI `axion tool list` 命令 003 已交付，消费本节 `ToolRegistry` 全量列表即可打通）

## 验收标准

### 自动化部分（harness 承载，`mvn clean verify` 全绿即通过）

需求文档 §13 功能验收点：内置 Tool（白名单约束下进程内执行）+ MCP Tool（协议转发）→ 执行结果回传 Agent → Agent 把结果作为新一轮输入（后两步 ReAct 已交付，本课验证工具侧）。

**harness 分层对齐课件 §四**：前三块纯单测、第四块 mock MCP 连接也不碰网；白名单拦截用 mock Sandbox 验证（真实现 24 节）：

| 测试类 | 关键回归点 |
|--------|-----------|
| `AxionToolContractTest` | **坑十二参数化**：遍历 Registry 里每个工具断言 name/description/inputSchema 非空——任何工具漏实现 `getInputSchema()` 立刻红（Provider 翻译会卡死的那一步） |
| `ToolRegistryTest` | 三种来源都以 `AxionTool` 身份注册；**坑十四**：按 Profile `tools` 字段过滤后子集精确匹配、"不多不少"（多一个和少一个都断言失败）；**重名注册拒绝 + WARN**（FR-1）；**Profile 声明未知名工具 → 启动校验报错**（FR-1，001 同款纪律） |
| `AnnotatedMethodToolAdapterTest` | 方式三包装器：execute 转发调方法、返回值序列化为文本包 `ToolResult`；**无 Spring AI 自动执行路径**（坑二断言）；方法抛异常原样上抛 |
| `FileToolsTest`/`ShellToolsTest`/`HttpToolsTest` | 各"正常能跑通 + 越界会被拦"两条（mock Sandbox 验证 **坑十：enforce 先于 IO 被调用**——InOrder 断言，004 同款；违规抛 `SandboxViolationException` 时 IO 零发生）；shell 超时销毁；http 响应体上限 |
| `McpToolAdapterTest`/`McpClientServiceTest` | mock MCP client：`tools/list` 返回的工具被包装注册；execute 转发参数原样、结果包 `ToolResult`（失败 retryable）；**坑十三：连接失败只 WARN、其余工具照常注册、启动不炸** |
| 004 回归 | `NotifyToolsTest`/`WebhookNotifyAdapterTest`/`NotifyChannelRegistryTest`/`NotifyChannelRepositoryTest` 全绿（FR-7 接线改造不得破坏已验收行为） |

**最值钱的回归测试**（课件 §四原文，坑十二/坑十三钉死）：

```java
@ParameterizedTest
@MethodSource("allRegisteredTools")   // 遍历 ToolRegistry，新工具自动纳入契约检查
void 每个工具的契约三件套都不能缺(AxionTool tool) {
    assertNotNull(tool.getName());
    assertNotNull(tool.getDescription());
    assertNotNull(tool.getInputSchema());   // 缺了它，Provider 翻译 Function Calling 时直接卡死
}

@Test
void 某个MCP_server失联_不能拖垮启动和其他工具() {
    when(badClient.listTools()).thenThrow(new ConnectException("refused"));

    mcpClientService.connectAll();          // 不抛异常——外部依赖的可用性不是自己的可用性

    assertTrue(toolRegistry.contains("good_mcp_tool"));   // 好的 server 照常注册
    assertFalse(toolRegistry.contains("bad_mcp_tool"));
}
```

跑法：`mvn test` 日常全跑（全绿才算实现完成）。

### 人工部分（做完怎么验）

- **方式三真跑一次**：写一个 `@Tool` 示例 Bean（如 `EchoTools` 临时演示类，验收后删除），启动后 `axion tool list` 可见、被包装进 Registry（依赖真启动 + 装配，不依赖真模型）
- **004 遗留补验（2026-09-05 自审补入）**：`axion chat` 里让 Agent 调 `notify` 把消息推到企业微信群——"LLM 在对话里自动调 notify"端到端版（004 flow-status 待办到期；依赖真模型 key + 真实 webhook，无 key 如实记待办）
- **Demo 一对话版补跑**：`axion chat` 里问天气——Agent 调 `http_get` 真查天气并给穿搭建议（依赖真模型 key + 真网络；无 key 时如实记待办，按 001 先例）
- **安全窗口人工留意（FR-7 纪律）**：20~23 节 PermissiveSandbox 全放行期间，人工演示用的 Agent 声明保守 tools（不进 shell/http_post）；24 节替换后重验白名单拦截
- **MCP 失联实机核验**：`mcp_servers.yaml` 配一个不可达 server，启动日志 WARN 带 server 名、`tool list` 不受影响、其余功能正常
- **落库核对**：工具调用后 `tool_invocations` 里 tool_name/success/duration_ms 正确（002 ToolExecutorTest 已覆盖，这里跑真链路时顺带目检一眼）
- **白名单拦截人工验证**：**归第 24 节**（PermissiveSandbox 期间无拦截行为——如实记录，不得伪装通过）
- **人工 review 关键代码**：`FileTools`/`ShellTools`/`HttpTools` 的 execute 首行 enforce（坑十，最该盯的一段）；`PermissiveSandbox` 的替换标注是否醒目；`AnnotatedMethodToolAdapter` 无 Spring AI 自动执行路径（坑二）
- 契约三件套、注册过滤、失联隔离、越界被拦（mock）——已由 harness 覆盖，`mvn test` 绿即打勾（课件 §五）

## 依赖与假设

### 前序交付物（已就位，本节直接依赖）

- **001-provider**：`AxionTool`（四方法含 `getInputSchema`）、`ToolResult`、`JsonSchema`、`ToolSchemaAdapter`（正向翻译，core）、`Profile.tools` 字段（List<String>）
- **002-react**：`ToolExecutor`（按名调度 + 审计 day one + 不持 Sandbox——涉外工具 execute 首行自 enforce）、`Sandbox` 接口墙四件套、`contracts/sandbox.md`（行为不变量三接线约定；消费方明列"第 20 节 FileTools/ShellTools/HttpTools"）
- **003-cli**：`CliAgentConfiguration`（装配先例，工具集空 Map 待本课替换）、`InitCommand`（已建 mcp_servers.yaml 模板）、`axion tool list` 命令、SnakeYAML 依赖先例
- **004-notify**：`NotifyTools` 五件套 + `notify_channels` 表口径 + `contracts/notify-channel.md`（不变量 9：装配处 RestClient Boot builder + timeout + 显式映射）、RestClient 依赖（axion-tool pom 已有 spring-web）

**现状确认（2026-09-05 实测）**：axion-tool 现状 = Sandbox 接口四件套 + builtin/NotifyTools + notify 五件；**无** ToolRegistry/FileTools/ShellTools/HttpTools/MCP 任何代码；`Profile.tools` 存在且防御拷贝；`CliAgentConfiguration` 工具集注入空 Map；`mcp_servers.yaml` 模板仅一行注释（可解析为空列表）；本地仓库 `spring-ai-mcp` 1.1.8 仅协议壳（aot/customizer，无 client 类）——与文档描述一致，MCP 依赖需新增。

### 前序缺口（H0 依赖检查）

无——"Sandbox 纯接口零实现"（002 FR-7）与"工具集空 Map 第 20 节替换"（003 FR-10）都是既定跨节契约；生产接线按拍板方案 A（临时 PermissiveSandbox）落地，24 节替换，不构成缺口。

### 改造点（经拍板允许修改的前序公共接口）

- **`CliAgentConfiguration`（003 交付物）**：工具集空 Map → `ToolRegistry` 注入（003 FR-10 既定口径："第 20 节替换"本身就是 003 留下的接口，非违约改造）
- **`ToolListCommand`（003 交付物，2026-09-05 收尾自审补钉）**：003 时为占位轻命令（"内置工具尚未就位"），本节改为**重命令**（ChatCommand 同款启动模式）读 ToolRegistry 全量列表——"轻命令秒回"分类因数据源变为 Spring bean 而调整，如实记录
- 其余前序公共接口零改动：`ToolExecutor`/`AxionTool`/`Sandbox`/`NotifyTools` 原样使用；`init` 模板不改（本节只消费）

### 外部依赖与假设

- **`spring-ai-starter-mcp-client`**（版本随 spring-ai-bom 1.1.8）：课件 `McpClient.connect` 为示意——本地实测 spring-ai-mcp 无 client 类，实施时先 `mvn dependency:resolve` 落库再 jar 反查 `McpSyncClient`/stdio transport 实际 API（写前 H3 门禁，核实不到 → 停止清单第 5 条停下报告）
- **SnakeYAML**：yaml 解析，003 已有先例；`RestClient`：004 已引入
- **运行时环境**：`GITHUB_TOKEN` 等 MCP 凭证走 `${ENV_VAR}` 占位（001 口径）；方式一/方式二人工验证需要真 MCP server 与真模型 key（无则如实记待办）；**平台假设（自审补钉）**：生产目标环境 Linux（K8s/服务器，bash 可用）；Windows 本机测试环境经 Git Bash 的 bash 执行（003 同款环境口径，测试用例据此编写）
- **MCP 同步门面口径（自审补钉）**：`McpSyncClient` 同步门面封装，业务层零 Reactor/异步代码（宪法 VII 口径）；依赖树中可能随 MCP starter 传入 reactor 为框架内部实现，不构成业务违规
- **安全口径**：20~23 节生产挂 `PermissiveSandbox` 全放行（拍板）——白名单未生效期间靠内网假设 + `tool_invocations` 审计留痕兜底，**并执行保守 Profile 纪律（FR-7：shell/http_post 不进任何 Agent 的 tools 声明）**，文档诚实说明（信任边界口径延续）
- **跨节契约**：本节交付的 `ToolRegistry`/内置 Tool 六件/`PermissiveSandbox` 替换点是后续节的契约——21/22 节（MemoryTools 注册进同一 Registry）、23/24 节（`WhitelistSandbox` 无缝替换 PermissiveSandbox）、25 节（定时触发后工具调用）、26 节（`/api/v1/tools` 消费全量列表）、29 节（插件化 Agent）、31 节（Demo 二/三 MCP 日报）——后续节不得改动已验收行为
- **跑通标准**：本节 + 001~004 撑起 Demo 一**对话版**（chat 里 `http_get` 真查天气 + 穿搭建议）——"LLM 在对话里自动调工具"的完整闭环；Demo 一钟推版 25 节、Demo 二/三 31 节
