# 常见问题

## Axion 和 Dify / Coze 有什么区别？

Axion 是**运行时**，不是编排平台。Dify 和 Coze 是工作流编排工具——让你可视化地设计 Agent 工作流。Axion 在他们下面一层：提供 Agent 运行所需的运行时环境（Provider、ReAct、Memory、Tool、审计、沙箱）。事实上 Dify 可以跑在 Axion 之上作为编排层。

## Axion 和 OpenClaw / Hermes Agent 有什么区别？

同类产品，不同定位。三者都采用 markdown + frontmatter 的目录形态定义 Agent。关键区别：

- **语言生态**：Axion 是 Java/Spring Boot——OpenClaw 是 Node.js，Hermes Agent 是 Python
- **目标用户**：Axion 面向严监管企业（银行、政府、医疗）。OpenClaw 和 Hermes Agent 面向个人开发者到小团队。
- **治理能力**：Axion 从第一天就内置审计、沙箱和多租户。这些在其他项目中往往被搁置。

## 为什么选 Java？不用 Python 或 Node.js？

因为目标企业已经在用 Java。银行、政府、电信公司的技术设施、运维团队、安全审查流程都是围绕 Java 建立的。一个 Python Agent 运行时在他们的环境中是个异物。Java 21 的虚拟线程提供了 Agent 工作负载所需的并发模型。

## 需要 PostgreSQL 吗？Redis？Docker？

**不需要。** 核心阶段的 Axion 只需要 Java 21 + Maven。数据存储在本地 SQLite。不需要外部服务、不需要容器、不需要云。一个 fat JAR 就跑起来了。

## Axion 如何处理安全？

三层防护：

1. **沙箱**：所有 Tool 调用在执行前都经过 `Sandbox.enforce()` 检查。核心阶段：`WhitelistSandbox`，路径/命令/域名三层白名单。
2. **凭证**：API key 和敏感配置使用 `${ENV_VAR}` 语法——永不明文写在 Agent 配置文件里。
3. **审计**：`tool_invocations` 和 `llm_calls` 从第一天写入。每个操作都可追溯。

## 可以添加自己的 Tool 吗？

可以，三种方式：

1. **零代码**：在 `mcp_servers.yaml` 中配置社区 MCP server
2. **轻代码**：用任意语言写一个 MCP server（JSON-RPC over stdio）
3. **重代码**：写一个带 `@Tool` 注解的 Java 类——进程内调用，性能最好

## 支持哪些 LLM Provider？

DeepSeek、通义（Qwen）、Kimi、智谱（GLM）、混元、豆包等十余个，通过 Spring AI 接入。多 Provider 共存，显式映射——不同 Agent 可以用不同模型。

## 已经可以用于生产环境了吗？

不。Axion 目前处于 **pre-alpha** 阶段（核心阶段）。API 可能变动。目前不推荐用于生产环境。

## 如何贡献？

Axion 欢迎一切形式的贡献：代码、文档、Issue、讨论。

1. Fork 仓库
2. 创建特性分支 (`git checkout -b feat/amazing-feature`)
3. 提交变更 (`git commit -m 'feat: add amazing feature'`)
4. 推送 (`git push origin feat/amazing-feature`)
5. 创建 Pull Request

提交前请阅读 [CLAUDE.md](https://github.com/yALearner/Axion/blob/main/CLAUDE.md) 了解项目宪章和编码原则。
