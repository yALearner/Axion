# 接口契约：ToolRegistry（统一工具注册表）

> **跨节契约**：本节交付后，第 21/22 节（MemoryTools 注册进同一 Registry）、第 23/24 节（`WhitelistSandbox` 无缝替换 `PermissiveSandbox`）、第 25 节（定时触发后的工具调用）、第 26 节（`/api/v1/tools` 消费全量列表）、第 29 节（插件化 Agent）、第 31 节（Demo 二/三 MCP 日报）为消费方。**后续节不得改动已验收行为**；修改本契约视为修改公共接口，必须停下报告。

## 接口形态

```java
ToolRegistry.register(AxionTool tool)          // 三来源统一入口；重名拒绝 + WARN（不静默覆盖）
ToolRegistry.contains(String name)            // tool list / 失联隔离测试用
ToolRegistry.all()                            // 全量列表（List<AxionTool>）
ToolRegistry.filter(Profile profile)          // 按 profile.tools() 过滤；声明未注册名 → 明确报错
```

## 行为不变量

1. **来源无感知**：ReAct 循环只跟 `AxionTool` 打交道，不感知工具来自内置/MCP/@Tool 包装（课件 §二第一）
2. **过滤不多不少（坑十四）**：子集恰好等于 Profile.tools 声明列表——多一个（没过滤干净）和少一个（过滤过头）都是错
3. **重名拒绝（FR-1 自审补钉）**：后注册同名工具 → 明确拒绝 + WARN，不静默覆盖（防 MCP 工具遮蔽内置工具）
4. **未知名报错（FR-1 自审补钉）**：Profile 声明了未注册的工具名 → 启动校验明确报错（001 provider 引用校验同款纪律，不静默少一个）
5. **契约三件套（坑十二）**：任何注册工具 name/description/inputSchema 非空——AxionToolContractTest 参数化遍历兜底，漏实现 getInputSchema 立刻红
6. **执行权唯一（坑二）**：所有工具的 execute 一律经 ToolExecutor 调度；任何来源不得绕过注册表直调工具
7. **涉外 IO 首行 enforce（坑十）**：File/Shell/Http 三件 execute 首行 `sandbox.enforce(...)` 先于 IO——顺序反了就是漏洞（002 contracts/sandbox.md 行为不变量三）
8. **MCP 失联隔离（坑十三）**：连接失败只 WARN 跳过该 server 工具，其余照常注册，启动不炸——外部依赖的可用性不是自己的可用性

## 替换契约（PermissiveSandbox → WhitelistSandbox）

- 第 20 节：装配处挂 `PermissiveSandbox` @Bean（全放行，javadoc 标注替换时机）
- 第 24 节：替换为 `WhitelistSandbox`（三层白名单）——**接口不变、调用方零改动**，只换装配处一行 @Bean
- 安全窗口（20~23 节）：保守 Profile 纪律（不建议 shell/http_post 进任何 Agent 的 tools 声明）+ 内网假设 + 审计留痕

## 演进

- 21/22 节：`MemoryTools`（save_memory/recall_memory）注册进同一 Registry，本契约零改动
- 扩展阶段：Tool Policy（allow/deny）、按需加载、工具并行调用——新增能力不改本契约
