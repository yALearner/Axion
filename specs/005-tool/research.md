# Research: 005-tool（技术选型与裁决记录）

> 本 feature 无未决 NEEDS CLARIFICATION——全部裁决已在需求文档拍板（2026-09-05，含设计期自审修订 ⑤~⑧）。本文件记录裁决内容与备选，作为 plan/tasks 的依据。

## 裁决 1：@Tool 注解骨架机械适配为 AxionTool 纯实现类（004 先例延续）

- **Decision**: 内置六件全部 implements `AxionTool` + 手写 JsonSchema，不加组件注解（G4-C1）；课件骨架的 `@Tool` 注解形态机械适配（004-notify 已拍板同一方向）
- **Rationale**: boot 启动类 `scanBasePackages="com.axion"` 全树扫描——@Component/@Tool 类被拾取时依赖未必就位；宪法 II 只允许 Spring AI 的 schema 生成，方法级 @Tool 的自动注册把注册权交给 Spring AI 也把执行钩子带进来
- **Alternatives considered**: 照抄课件 @Tool 方法形态（否决：G4-C1 全树扫描风险 + 执行路径与 ToolExecutor 唯一执行权冲突）

## 裁决 2：Sandbox 无实现期间生产装配 = 临时 PermissiveSandbox（拍板方案 A）

- **Decision**: 装配处挂 `PermissiveSandbox` @Bean（全放行、javadoc 标注 24 节替换 WhitelistSandbox）；Demo 一对话版 20 节即可真跑
- **Rationale**: 用户拍板（2026-09-05）：工具上线即闭环优先；白名单未生效期间（20~23 节）以内网假设 + tool_invocations 审计留痕兜底，并执行保守 Profile 纪律（shell/http_post 不进任何 Agent 的 tools 声明）
- **Alternatives considered**: B 生产接线推迟到 24 节（否决：Demo 一对话版顺延三节，005 交付的"闭环"价值大打折扣）

## 裁决 3：MCP 客户端 API 形态（新增依赖 + H3 核实 + 降级路径）

- **Decision**: 新增 `spring-ai-starter-mcp-client`（版本随 spring-ai-bom 1.1.8）；课件 `McpClient.connect` 为示意——本地实测 `spring-ai-mcp` 1.1.8 jar 仅 aot/customizer 包（无 client 类），实施时先 `mvn dependency:resolve` 落库再 jar 反查 `McpSyncClient`/stdio transport 实际 API；核实不到 → 停止清单第 5 条停下报告（方式三同款降级纪律）
- **Rationale**: 004 的 H3 实测教训（mockwebserver "BOM 管理"不成立）——示意骨架与现实 API 的差距必须写前核实
- **Alternatives considered**: 手写 JSON-RPC over stdio MCP 客户端（否决：违反"复用管道"原则，MCP 协议实现复杂度远超本课范围）

## 裁决 4：方式三 @Tool 扫描包装（执行仍走 ToolExecutor）

- **Decision**: `AnnotatedMethodToolAdapter` 包装容器内 @Tool 方法：仅借 Spring AI 做扫描发现 + schema 生成（宪法 II 允许的两件事）；execute 内调方法、返回序列化为文本包 ToolResult、异常上抛由 ToolExecutor 审计；扫描 API 实施前 H3 核实，核实不到降级为装配处手动注册并记录 flow-status
- **Rationale**: 宪法 I/II——执行权唯一在 ToolExecutor，Spring AI 自动执行 = tool 被调两次（陷阱表首条）
- **Alternatives considered**: 用 Spring AI MethodToolCallback 直接注册（否决：执行不经 ToolExecutor 违反宪法 I）；不做方式三（否决：课件验收"方式三真跑一次"是本课人工项）

## 裁决 5：注册与过滤语义（自审补钉）

- **Decision**: 重名注册明确拒绝 + WARN（不静默覆盖——防 MCP 工具意外遮蔽内置工具）；Profile 声明未注册工具名 → 启动校验明确报错（001 provider 引用校验同款纪律）
- **Rationale**: 不静默是 Axion 全线纪律（001 配置校验、004 渠道解析）；静默少一个工具会让 Agent 以为调了其实没调
- **Alternatives considered**: 重名后注册覆盖（否决：遮蔽内置工具 = 静默能力替换）；未知名静默跳过（否决：typo 工具名应显性暴露）

## 裁决 6：内置工具实现级细节（参数规格表）

- **Decision**: shell 超时默认 30s（ProcessBuilder.waitFor(timeout)，超时强制销毁 + 明确报错，退出码非 0 → failure 带 stdout/stderr）；http 响应体上限 1MB（超限明确报错）；write_file 覆盖已存在文件、父目录不存在明确报错不递归建目录；http_post 支持 JSON body（contentType 默认 application/json，form/文件上传明确不做）
- **Rationale**: LLM 靠 schema 正确传参，参数与行为语义设计即工具可用性；上限与超时防"慢/大响应拖死 ReAct 轮次"（004 timeout 同款哲学）
- **Alternatives considered**: 无上限/无超时（否决：超长响应撑爆上下文、挂死进程是 Agent 常见事故）

## 裁决 7：HttpTools 用 RestClient（004 先例）

- **Decision**: HttpTools 复用 RestClient（004 契约不变量 9 的 Boot builder 产出：connect/read timeout + 池化）；不用 java.net.http.HttpClient
- **Rationale**: 全仓 HTTP 出口统一（004 WebhookNotifyAdapter 同款）；装配处一个 builder 同时服务 notify 与 http 工具
- **Alternatives considered**: JDK HttpClient（否决：拆散装配口径、失去 Boot 池化默认）

## 裁决 8：平台假设（自审补钉）

- **Decision**: 生产目标 Linux（K8s/服务器，bash 可用）；Windows 本机测试经 Git Bash 的 bash 执行（003 同款环境口径）；ShellToolsTest 据此编写（不依赖 cmd/powershell）
- **Rationale**: 需求文档"执行 bash 命令"字面即 Linux 语义；003 已在 Windows 上验证过 Git Bash 路径
- **Alternatives considered**: 跨平台命令抽象（否决：超出核心阶段范围，bash 是课件与四文档既定口径）
