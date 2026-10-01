# 005-tool 代码 Review 指南

> 生成：2026-09-05（交付后复盘 + 人工验收闭环后）；对应 PR #6。
> 复盘全记录见 `specs/005-tool/flow-status.md`（含三次用户实跑暴露缺陷的修复轨迹），本指南是 review 导航。

## 一、全景：LLM 想调工具 → 执行 → 审计

```
LLM tool call（Profile.tools 过滤后的子集注入 prompt）
  → ToolExecutor.execute（按名调度 + 成败都落 tool_invocations 审计，002 交付零改动）
  → 各 AxionTool.execute：
      内置六件/NotifyTools —— execute 首行 sandbox.enforce(...) 先于 IO（坑十）
      方式二 MCP —— McpToolAdapter 经 JSON-RPC 转发（McpSyncClient 同步门面）
      方式三 @Tool —— AnnotatedMethodToolAdapter 调方法（仅借 Spring AI 扫描与 schema，坑二）
  → 结果回填对话，失败喂回模型由 LLM 决定重试（天气 Demo 实拍 4 轮重试成功）
```

注册侧：`ToolRegistry`（ConcurrentHashMap）三来源统一注册（内置六件 + NotifyTools 装配处显式 @Bean、MCP @Component 自注册、方式三显式扫描包装）；Profile.tools 过滤"不多不少"（坑十四）；重名拒绝 + 未知名启动校验（FR-1）。

## 二、逐文件梳理

### axion-tool/com/axion/tool（注册与包装，3 个文件）

| 文件 | 职责与关键点 |
|------|-------------|
| `ToolRegistry` | 三来源注册/contains/all/filter；**重名 putIfAbsent 拒绝 + WARN**（CRLF 抑制注解带理由）；**filter 未知名抛异常**（001 同款纪律） |
| `PermissiveSandbox` | 拍板方案 A：全放行，javadoc 标注「第 24 节替换 WhitelistSandbox、替换后本类删除」；安全窗口纪律（20~23 节保守 Profile） |
| `AnnotatedMethodToolAdapter` | 方式三包装器：getName/description/schema 映射 ToolDefinition；execute 调 `callback.call`、返回 JSON 序列化文本；**ToolExecutionException 解包还原原样上抛**（坑十一口径） |

### axion-tool/com/axion/tool/builtin（内置六件 + 004 的 NotifyTools）

| 文件 | 关键点 |
|------|--------|
| `ReadFileTool`/`WriteFileTool`/`ListDirTool` | execute 首行 `enforce(FILE_READ/WRITE, path)` 先于 IO（坑十）；write_file 覆盖语义 + 父目录缺失报错；list_dir getFileName null 防护（SpotBugs NP 修复） |
| `ShellTools` | 首行 `enforce(SHELL_COMMAND)`；构造注入 timeoutMs（30_000 默认，可测性）；**shell 可执行文件三级解析**（PATH → where git → 标准路径，PowerShell 实测修复）+ 绝对路径 Git Bash 子进程 PATH 富化；COMMAND_INJECTION 抑制带信任边界理由 |
| `HttpGetTool`/`HttpPostTool` | 首行 `enforce(HTTP_REQUEST, url)` 先于请求；1MB 上限（MAX_RESPONSE_BYTES 常量）；4xx/5xx 异常上抛；EI_EXPOSE_REP2 抑制（004 先例） |
| `NotifyTools` | 004 已交付原样使用，本课完成生产接线 |

### axion-tool/com/axion/tool/mcp（方式二，3 个文件）

| 文件 | 关键点 |
|------|--------|
| `McpServerConfig` | 不可变 record（防御拷贝）：name/transport/command/args/env |
| `McpClientService` | `@Component` + `@PostConstruct connectAll`；**坑十三**：单 server 失败 catch + WARN 带名、不抛、不拖垮启动；配置缺失/空列表正常、结构非法明确报错；`${ENV_VAR}` 占位未解析报错；测试 seam（protected connect/loadConfigs） |
| `McpToolAdapter` | 三件套映射 tools/list 返回；execute arguments(Map) 转发（TypeReference 转换）、结果包 ToolResult（isError → failure retryable=true）；TextContent 拼接 |

### axion-cli（装配与命令改造，2 个文件）

| 文件 | 关键点 |
|------|--------|
| `CliAgentConfiguration` | FR-7 全量：工具集空 Map → ToolRegistry（六件 + NotifyTools 显式 @Bean + 004 契约不变量 9 RestClient + PermissiveSandbox @Bean）；**Profile tools 启动校验**（未知名 ERROR + CR/LF 净化，core 零改动）；**方式三显式装配**：methodToolCallbackProvider @Bean（扫描 bean 类型收集 @Tool beans 构建 Provider，无 bean 时返回 null 走 INFO 降级） |
| `ToolListCommand` | 003 占位轻命令 → **重命令**（ChatCommand 同款 Class.forName + web(NONE)）读 ToolRegistry 全量 |

### 测试（8 个新类，坑 ↔ 对号）

| 坑/点 | 测试落点 |
|------|---------|
| 坑十 enforce 先于 IO / 违规零 IO | FileToolsTest（write 违规后文件不存在）/ ShellToolsTest（InOrder）/ HttpToolsTest（违规后 getRequestCount==0） |
| 坑十二契约三件套 | AxionToolContractTest（参数化遍历 7 真实工具） |
| 坑十三失联隔离 | McpClientServiceTest.failedServerDoesNotBreakOthers（课件最值钱测试之二逐字） |
| 坑十四过滤不多不少 + 重名 + 未知名 | ToolRegistryTest（5 例） |
| 坑二无自动执行 / 转发包装 | AnnotatedMethodToolAdapterTest（3 例，含 ToolExecutionException 还原） |
| shell 超时销毁 / 退出码 / 平台解析 | ShellToolsTest（5 例；超时用 100ms 小超时 + sleep 5） |
| http 1MB 上限 / POST JSON / 4xx 上抛 | HttpToolsTest（5 例） |

## 三、重点 review 清单（按风险排序）

1. **六工具 execute 首行 enforce 先于 IO（坑十）**——`builtin/*.java` 各 execute；顺序反了就是漏洞（004 坑十延续），本课最该盯的六段
2. **PermissiveSandbox 替换标注（拍板方案 A）**——javadoc 是否醒目（24 节替换 + 本类删除）；20~23 节安全窗口纪律是否在需求文档/人工验收如实说明
3. **坑二：方式三执行路径**——`AnnotatedMethodToolAdapter.execute` 只调 callback、无 Spring AI 自动执行；CliAgentConfiguration 的 methodToolCallbackProvider 仅做扫描收集
4. **坑十三：失联隔离**——McpClientService.connectAll 的 catch 范围正确（单个 server 失败不炸）；注意 SDK 已知行为（initialize 20s 超时、reactor onErrorDropped 噪音）非本课缺陷
5. **装配处改造（FR-7）**——CliAgentConfiguration：RestClient 不变量 9（Boot builder + timeout）、`Map.of("webhook", adapter)` 显式映射、NotifyChannelRegistry 真实 Repository；core 零改动（PromptBuilder.selectTools 002 路径原样）
6. **ShellTools 平台解析**——三级解析 + 子进程 PATH 富化（PowerShell 环境实测修复；生产 Linux 走 "bash" 分支不受影响）
7. **过滤与注册语义**——ToolRegistry 重名拒绝/未知名报错与 PromptBuilder WARN 兜底并存（core 零改动口径）

## 四、刻意留白（review 时不要当成缺陷报）

- **WhitelistSandbox 三层白名单 + 拦截验证** → 23/24 节（002 FR-7 不翻案；本课 mock 验证链路）
- **save_memory/recall_memory** → 21/22 节 Memory（同一 Registry 注册）
- **Tool Policy / 按需加载 / 对外暴露 MCP server / 容器沙箱 / 并行调用 / SSE transport** → 扩展阶段
- **方式三 @Tool 自动扫描依赖 Spring AI 自动配置** → 1.1.8 实测无现成扫描（autoconfigure-model-tool 只注册 ToolCallingManager）→ 显式装配落地；未来版本若提供自动扫描可简化，非缺陷
- **MCP initialize 20s SDK 默认超时 + reactor onErrorDropped 日志噪音** → SDK 行为（flow-status 已记录）
- **tool list 自轻命令改重命令** → 数据源变为 Spring bean 的必然分类调整（需求文档改造点已记录）
- **执行层无 Profile 过滤**（LLM 幻觉请求未声明工具时 ToolExecutor 仍会执行）→ Tool Policy 扩展阶段（技术方案 §6.7 要点二）
- **DeepSeek 工具调用意愿**（前两次拒绝调 http_get）→ 模型行为，prompt 三件套解决（axion-design template §六）

## 五、建议 review 顺序

1. `specs/005-tool/contracts/tool-registry.md`（契约先行：不变量 1~8 + 替换契约）
2. `ToolRegistry` → `PermissiveSandbox`（注册与安全语义）→ builtin 六件（坑十六段）→ mcp 三件（坑十三）→ `AnnotatedMethodToolAdapter`（坑二）
3. `CliAgentConfiguration`（装配全景：FR-7 + 启动校验 + 方式三显式装配）→ `ToolListCommand`
4. 测试 ↔ 坑对号（§二表格逐条比对，8 类 60 例）
5. `specs/005-tool/flow-status.md`（修复轨迹全记录：ShellTools 平台 / 方式三装配 / tool list 改造）

## 六、当前验收状态

- **人工验收全部闭环**（2026-09-05）：tool list 7 工具、方式三 echo_tool 实跑、notify 补验（群收到 + ReAct 铁证）、天气 Demo（http_get 失败重试成功、真实数据）、MCP 失联实机、安全窗口保守声明
- `mvn clean verify` 全绿：120 tests（005 新增 60）+ 全静态门禁；PowerShell 环境构建验证通过
- **剩余待办（如实记录，不阻塞合并）**：① 004 接口中立性自查（用户 1 分钟思维练习）② 白名单拦截人工验证归 24 节 ③ 方式三自动扫描的未来简化评估（Spring AI 版本升级时）
