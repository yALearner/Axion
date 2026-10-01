# Quickstart: 005-tool 验证指南

> 运行前提（本机环境）：构建前 export JAVA_HOME 与 PATH（002 先例口径）。
> 自动化验证全绿 = 机器判卷部分完成；人工部分（真模型/真 MCP/004 遗留补验）见文末清单。

## 自动化验证（harness）

```bash
# 全量门禁（含 001~004 回归 + 静态检查门禁）——收尾 DoD 的判定依据
mvn clean verify

# 只跑本 feature 模块（日常迭代）
mvn test -pl axion-tool,axion-cli -am
```

预期结果：axion-tool 新增 8 个测试类 + 004 的 4 个测试类全量回归绿。

| 测试类 | 验证点 |
|--------|--------|
| `AxionToolContractTest` | **坑十二参数化**：遍历 Registry 每个工具 name/description/inputSchema 非空——漏 getInputSchema 立刻红 |
| `ToolRegistryTest` | 三来源注册；**坑十四**过滤不多不少；**重名拒绝 + WARN**；**未知名工具启动报错**（FR-1） |
| `FileToolsTest`/`ShellToolsTest`/`HttpToolsTest` | 各"正常跑通 + 越界被拦"两条（mock Sandbox：InOrder 断言 **坑十 enforce 先于 IO**、违规 IO 零发生）；shell 超时销毁；http 1MB 上限 |
| `McpClientServiceTest`/`McpToolAdapterTest` | mock client：listTools 包装注册；execute 转发原样、结果包 ToolResult（失败 retryable）；**坑十三失联只 WARN 不炸** |
| `AnnotatedMethodToolAdapterTest` | 方式三：execute 转发调方法、返回序列化包 ToolResult、异常上抛；**坑二无自动执行路径** |

## 人工验证（机器判不了的部分；方法论见 `references/manual-acceptance.md`）

1. **方式三真跑一次**：写一个 `@Tool` 示例 Bean（临时演示类，验收后删除）→ 启动后 `axion tool list` 可见、被包装进 Registry（依赖真启动 + 装配，不依赖真模型）
2. **004 遗留补验**：`axion chat` 里让 Agent 调 `notify` 把消息推到企业微信群——"LLM 在对话里自动调 notify"端到端版（004 flow-status 待办到期；依赖真模型 key + 真实 webhook，无 key 如实记待办）
3. **Demo 一对话版补跑**：`axion chat` 问天气——Agent 调 `http_get` 真查天气 + 穿搭建议（依赖真模型 key + 真网络；无 key 如实记待办，按 001 先例）
4. **MCP 失联实机核验**：`mcp_servers.yaml` 配一个不可达 server → 启动日志 WARN 带 server 名、`tool list` 不受影响
5. **落库核对**：工具调用后 `tool_invocations` 里 tool_name/success/duration_ms 正确（跑真链路时目检一眼）
6. **安全窗口人工留意（FR-7 纪律）**：20~23 节 PermissiveSandbox 全放行期间，人工演示 Agent 声明保守 tools（不进 shell/http_post）；24 节替换后重验白名单拦截（白名单拦截人工验证归 24 节，本节如实记录不伪装通过）

## 本节不做（验证时不要误判为缺陷）

- WhitelistSandbox 三层白名单与拦截验证（23/24 节；本课 mock 验证链路）
- save_memory/recall_memory（21/22 节 Memory）
- Tool Policy / 按需加载 / 对外暴露 MCP server / 容器沙箱 / 并行调用 / SSE transport（扩展阶段）
- Skill 加载与 Agent 目录（002 ContextLoader 已交付，不翻案）
- `init` 模板示例化（003 模板仅注释行，本节只消费；配置示例见 data-model.md）
