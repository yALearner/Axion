# Feature Specification: Sandbox 三层白名单（WhitelistSandbox 替换全放行）

**Feature Branch**: `007-sandbox`

**Created**: 2026-09-06

**Status**: Draft

**Input**: 需求文档 docs/requirements/007-sandbox.md（课件第 23/24 节：Sandbox 三层白名单；整体以 D:\项目\ 新版 PDF 为准，PyMuPDF 提取已解决）

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 白名单外的命令被拦、失败可审计（Priority: P1）

Agent 调 `shell` 执行模型生成的 `rm` 命令，但白名单只允许 `ls`：Sandbox 抛 `SandboxViolationException`（"命令不在白名单内: rm"），`tool_invocations` 落 `success=false`，`error_message` 人能读懂，模型下一轮看到失败原因从而知道这条路走不通（需求文档场景一，本节验收场景）。

**Why this priority**: 本节的验收场景与集成验证锚点（课件 24 §五：配只允许 ls 的白名单真跑一次白名单外命令）；Shell 首 token 校验 + 失败路径复用 ToolExecutor 既有审计（FR-7 零新增）是本课"接口设计对了"判断的兑现（课件 24 结语）。

**Independent Test**: `WhitelistSandboxTest` Shell 命令组全 mock 单测（放行/拒绝/前导空格/大小写/空命令/环境变量前缀）；集成验证人工项真实链路跑 ls 白名单。

**Acceptance Scenarios**:

1. **Given** 白名单只含 `ls`，**When** enforce 收到 `rm -rf /`，**Then** 抛 SandboxViolationException，message = "命令不在白名单内: rm"，真正的进程执行没有发生（verify(executor, never())）
2. **Given** 白名单含 `ls`，**When** enforce 收到 `"  ls -la"`（前导空格），**Then** trim 后首 token `ls` 放行
3. **Given** 白名单含 `ls`，**When** enforce 收到 `LS` 或 `VAR=x ls` 或空命令，**Then** 拒绝（大小写敏感、环境变量前缀按精确匹配拒、空命令拒）
4. **Given** 违规经 ToolExecutor 执行，**When** 工具调用完成，**Then** `tool_invocations` 落 `success=false` + `error_message` 含"不在白名单内"（FR-7 零新增审计）

---

### User Story 2 - 文件路径白名单拦路径穿越（Priority: P1）

Agent 读 `/workspace/../../outside/secret.txt` 试图爬到白名单目录之外：`checkFilePath` 经 `normalize().toAbsolutePath()` 标准化后落回白名单外，拒绝（需求文档场景二，课件 24 §四最值钱回归之一）。

**Why this priority**: `../` 穿越是应用层校验最容易漏的点（课件 24 §3.2 原文点名）；连同「相对根 + 绝对 target 放行」（⑦a）与 Windows 大小写（⑦b）是文件类三个工具的可用性与安全性底座。

**Independent Test**: `WhitelistSandboxTest` 文件路径组全 mock 单测：放行/拒绝/穿越被拦/相对根绝对 target 放行/白名单根构造期 normalize+toAbsolutePath/Windows 大小写变体放行（平台条件测试）。

**Acceptance Scenarios**:

1. **Given** 白名单根 `/workspace`，**When** enforce 收到 `/workspace/../../outside/secret.txt`，**Then** 抛 SandboxViolationException（normalize 吸收穿越——课件原文用例）
2. **Given** 白名单根为相对路径 `.axion/workspace`，**When** enforce 收到该目录下任意绝对路径，**Then** 放行（构造期 toAbsolutePath 后 startsWith 命中——「根未绝对化」回归钉，⑦a）
3. **Given** 运行于 Windows 且白名单根/目标大小写不一致，**When** enforce 校验，**Then** 按 lower-case 归一后命中放行（⑦b 平台条件测试；Linux 维持大小写敏感）
4. **Given** 目标在白名单外，**When** enforce 收到，**Then** 拒绝且真正的文件 IO 没有发生

---

### User Story 3 - HTTP 域名白名单带点号边界（Priority: P2）

白名单配置 `*.example.com`：`api.example.com` 放行，但 `evil-example.com` 必须被拒——`endsWith` 实现的经典漏洞，匹配必须带点号边界（需求文档场景三，课件 24 §四最值钱回归之二）。

**Why this priority**: 通配符匹配是域名白名单最易写错的点（`"evil-example.com".endsWith("example.com")` 为真）；`http_get`/`http_post`/`notify` 三个 HTTP 类工具都走这条校验。

**Independent Test**: `WhitelistSandboxTest` HTTP 域名组全 mock 单测：精确匹配放行/白名单外拒绝/通配符命中与不命中/host 解析不到拒绝。

**Acceptance Scenarios**:

1. **Given** 白名单 `*.example.com`，**When** enforce 收到 `https://api.example.com/x`，**Then** 放行
2. **Given** 白名单 `*.example.com`，**When** enforce 收到 `https://evil-example.com/x`，**Then** 拒绝（点号边界——endsWith 漏洞钉）
3. **Given** 白名单精确域名 `wttr.in`，**When** enforce 收到同域名 URL，**Then** 放行；收到白名单外域名，**Then** 拒绝
4. **Given** URL 解析不到 host（畸形 URL），**When** enforce 校验，**Then** 拒绝（不因 NPE 漏放，实现级明确——课件骨架无此防御，文档补强）

---

### User Story 4 - 三块配置装配、空配置全拒绝（Priority: P2）

`file.allowed_paths` / `shell.allowed_commands` / `http.allowed_domains` 三块配置经三个 `@ConfigurationProperties` record 注入 `WhitelistSandbox`，装配处替换 `PermissiveSandbox`（并删除该类）；空配置 = 什么都不允许而非"不校验"；任一白名单为空构造期打 WARN（启动诊断）。

**Why this priority**: 装配替换是本节的落地动作（005 拍板方案 A 的收口）；空配置口径是课件 24 §五人工项点名的安全底线（fail-closed），WARN 解决"配错无诊断"的运维痛点（⑦c）。

**Independent Test**: `CliAgentConfigurationTest` 增补 sandbox bean 为 WhitelistSandbox 的装配断言 + PermissiveSandbox 类不存在断言；`WhitelistSandboxTest` 接线回归组：空配置三类 action 全部抛异常、枚举四值全覆盖（FILE_READ/FILE_WRITE 同路由断言）。

**Acceptance Scenarios**:

1. **Given** 三块白名单全部为空，**When** enforce 收到任意三类 action，**Then** 全部拒绝（"不校验"口径的回归钉）且构造期有 WARN
2. **Given** 应用启动，**When** 检查 sandbox bean，**Then** 为 WhitelistSandbox 实例（装配替换断言）；PermissiveSandbox 类不存在
3. **Given** 枚举四值 FILE_READ/FILE_WRITE，**When** enforce 校验，**Then** 同路由到 checkFilePath（四值全覆盖断言）
4. **Given** 七工具 execute 首行 enforce 已接线（005 交付），**When** 白名单外输入进入任一工具，**Then** 被拦且真正 IO 没有发生（FileTools/ShellTools/HttpTools/NotifyTools 各一条）

---

### User Story 5 - 升级隔离强度时上层无感（Priority: P2）

某天要跑相对不可信代码或多租户，按信号升级到容器/microVM：新增一个实现类、装配处换一行，`Sandbox` 接口、七个 Tool、`ToolExecutor` 审计全部原样（需求文档场景四，课件 23 §十四/§十五）。

**Why this priority**: 接口墙是 002「接口先行」+ 005「先接线后实现」顺序红利的兑现验收（课件 24 结语：接入成本小到不成比例）；本节实现后上层零改动是硬断言。

**Independent Test**: 接口中立性自查（人工思维练习：`enforce(SandboxAction)` 套 KataMicroVmSandbox 不需要加方法）+ 实现收尾时 git diff 目检七工具类/Sandbox 接口/ToolExecutor 零改动。

**Acceptance Scenarios**:

1. **Given** 本节实现完成，**When** git diff 检查，**Then** 七工具类、Sandbox 接口、ToolExecutor 零改动（上层只认接口的红利兑现）
2. **Given** 接口中立性自查，**When** 用 microVM 实现反向套 `enforce(SandboxAction)` 签名，**Then** 不需要加方法（人工思维练习）

---

### Edge Cases

- 空命令（`"   "`）→ trim 后首 token 空串 → 拒绝
- 前导空格命令（`"  ls -la"`）→ trim 容忍 → 放行
- 大小写变体（`LS`）→ 精确匹配拒绝（Shell）；Windows 文件路径大小写变体 → lower-case 归一放行（文件）
- `VAR=x cmd` 环境变量前缀 → 按首 token `VAR=x` 拒绝
- host 解析不到（`URI.create(url).getHost()` 为 null）→ 拒绝，不 NPE 不漏放
- 相对白名单根（`.axion/workspace`）→ 构造期 toAbsolutePath，相对根按启动目录解析
- Windows 上 `/workspace` 类 Unix 绝对路径解析为当前盘根——穿越测试断言结果不变，平台语义注记
- 三块白名单全空 → 全拒绝（fail-closed）+ 构造期 WARN
- 枚举四值全覆盖（FILE_READ/FILE_WRITE 同路由断言）

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: `WhitelistSandbox` 实现 `Sandbox` 接口，`enforce` 按 `ActionType` 路由——`FILE_READ`/`FILE_WRITE` 同路由到 `checkFilePath`，`SHELL_COMMAND` → `checkShellCommand`，`HTTP_REQUEST` → `checkHttpUrl`；三个校验方法全部 private（外部只看得到 `enforce` 一个入口）；任意校验失败抛 `SandboxViolationException`（RuntimeException），Tool 执行终止
- **FR-002**: 三块白名单配置——`FileSandboxProperties`（prefix `file`，`allowedPaths`）、`ShellSandboxProperties`（prefix `shell`，`allowedCommands`）、`HttpSandboxProperties`（prefix `http`，`allowedDomains`）三个 `@ConfigurationProperties` record，装配处 `@EnableConfigurationProperties` 注册；对应 `application.yaml` 配置键 `file.allowed_paths` / `shell.allowed_commands` / `http.allowed_domains`。空配置 = 什么都不允许而非"不校验"；任一白名单为空构造期打 WARN（⑦c）
- **FR-003**: `checkFilePath`：`Path.of(rawPath).normalize().toAbsolutePath()` 后 `allowedRoots.stream().anyMatch(target::startsWith)`；白名单根构造期同样经 `normalize().toAbsolutePath()` 预处理（相对根按启动目录解析，⑦a）；`../` 穿越由 normalize 吸收；Windows 下 root/target lower-case 归一后比较（⑦b，实现级明确），Linux 维持大小写敏感
- **FR-004**: `checkShellCommand`：`command.trim().split("\\s+")[0]` 取首 token 精确匹配——大小写敏感、前导空格经 trim 容忍、`VAR=x cmd` 环境变量前缀按首 token 精确匹配拒绝（实现级明确）
- **FR-005**: `checkHttpUrl`：`URI.create(url).getHost()` 取 host；模式精确匹配（全等）或 `*.` 前缀通配（`host.endsWith(pattern.substring(1))` 带点号边界，`evil-example.com` 不命中 `*.example.com`）；host 解析不到（null）→ 拒绝（实现级明确新增防御）
- **FR-006**: 装配替换——`CliAgentConfiguration` 的 `sandbox()` @Bean 从 `PermissiveSandbox` 换成 `WhitelistSandbox`（构造注入三 properties，`@EnableConfigurationProperties` 注册）；`PermissiveSandbox` 类删除；七工具类、`Sandbox` 接口、`ToolExecutor` 全部零改动
- **FR-007**: 失败路径零新增——`SandboxViolationException` 由 `ToolExecutor` 既有 catch 接住（002 已交付），落 `tool_invocations`：`success=false` + `error_message`（如"命令不在白名单内: rm"）；不改 ToolExecutor、不新增审计逻辑；`retryable=true` 为 002 现状行为，维持不改
- **NFR-001**: 全程同步阻塞，不引入异步模型；并发由 Java 21 虚拟线程承担（宪法 VII）
- **NFR-002**: 劝阻级防线诚实标注——文档明确白名单"防的是模型犯傻误操作、防不住蓄意攻击"；核心阶段不建议用它跑完全不可信代码、不建议对外做多租户
- **NFR-003**: 结构化 JSON 日志沿用；违规原因（路径/命令/域名）进 `error_message` 审计、不进日志参数（用户可控值 CRLF 口径，005 先例）

### Key Entities

- **SandboxAction**（002 已交付，不改）：`type` + `target` 纯数据契约，不出现"白名单""容器""镜像"字样
- **WhitelistSandbox**（本节新增）：核心阶段唯一实现；构造期持有 `allowedRoots`（normalize+toAbsolutePath）、`allowedCommands`（Set.copyOf）、`allowedDomainPatterns`（List.copyOf）三份不可变拷贝
- **SandboxViolationException**（002 已交付，不改）：普通 RuntimeException，message 为人类可读的违规原因
- **三块白名单配置**（本节新增）：`file.allowed_paths` / `shell.allowed_commands` / `http.allowed_domains`

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 七工具全部真实白名单拦截——`PermissiveSandbox` 全放行不再存在，装配处唯一 Sandbox 实现为 WhitelistSandbox
- **SC-002**: 两个最值钱绕过场景（`../` 路径穿越、`evil-example.com` 形似域名）机器断言拦截，测试在实现之前写并直接决定校验逻辑写法
- **SC-003**: 集成验证（真实链路）——配只允许 `ls` 的白名单，Agent 跑白名单外命令时链路上抛 `SandboxViolationException`、`tool_invocations` 有 `success=false` 记录、`error_message` 人能读懂
- **SC-004**: 上层零改动——本节实现后七工具类、`Sandbox` 接口、`ToolExecutor` 与实现前逐字节一致（git diff 目检）
- **SC-005**: 全量测试全绿（不写死用例数——用例数随前序节交付动态变化，⑦e）

## Assumptions

- **前序交付物已实测就位**（2026-09-06）：接口墙四件 + PermissiveSandbox 在 `axion-tool` 全部就位；七工具 enforce 调用点全部存在（FILE_READ×2/FILE_WRITE×1/SHELL_COMMAND×1/HTTP_REQUEST×3）；`ToolExecutor` 两处 catch 已就位；`application.yaml` 无 allowed 配置键（本节新增）；`CliAgentConfiguration.sandbox()` 当前返回 PermissiveSandbox——本节为纯增量
- **零新第三方依赖**：`java.nio.file.Path`、`java.net.URI`、`String.split` 全部 JDK 原生
- **课件口径**（用户拍板 2026-09-06）：以新版 PDF（PyMuPDF 提取）为准；修订说明 ①⑥ 列明差异清单、⑦ 列明实施前优化（a~i）——spec/plan/tasks 一律以文档修订后口径为准
- **信任边界**：应用层白名单是劝阻级防线（防犯傻不防蓄意）；`shell` 跑脚本 = 信任 Agent 作者；核心阶段单实例 + 内网假设兜底
- **白名单不可热更新**：三块配置构造期拷贝，改 application.yaml 须重启生效（serve/gateway 常驻模式注意，⑦i）
- **MCP 挂载风险声明（跨节契约）**：本节白名单不覆盖 MCP 工具（McpToolAdapter 无 enforce，明确不做）——25/31 节 Demo 挂 MCP server 时须显式声明风险边界
- **跑通标准**：本节自身无独立 Demo，验收以 harness 全绿 + 集成验证（ls 实跑）为准；Demo 一/三的 HTTP 调用自本节起从"全放行"变为"真实白名单拦截"——25 节起各 Demo 的 agent 配置需把用到的域名/路径/命令列入白名单
- **明确不做**（文档列明，本节不实现）：容器/microVM、资源配额、Profile 级 Tool Policy、SecurityManager、防蓄意绕过扫描增强、脚本目录白名单（⑦f）、MCP enforce 覆盖（⑦g）、违规计数/metrics（⑦h）、修改 Sandbox 接口/ToolExecutor 审计逻辑、网络出口控制
