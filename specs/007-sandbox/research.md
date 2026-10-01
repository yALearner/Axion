# Research: 007-sandbox（技术选型与裁决记录）

> 本 feature 无未决 NEEDS CLARIFICATION——全部裁决已在需求文档钉死（2026-09-06：修订说明 ①~⑦，含课件口径拍板、PDF 全文复核、实施前优化「立即 vs 放后期」分类）。本文件记录裁决内容与备选，作为 plan/tasks 的依据。

## 裁决 1：接口形态——enforce(SandboxAction)，不引入策略对象

- **Decision**: `enforce(SandboxAction)`（type+target 纯数据），维持 002 已交付形态零改动；23 节新十二提出的「策略对象带隔离等级」不采用
- **Rationale**: 24 节代码骨架 + 002 已交付 + 技术方案 §6.7 三处一致为无策略参数形态；宪法 VI 明文「接口只有一个方法 enforce(SandboxAction)」。多租户未来走 per-tenant 实例（SandboxProvider），接口签名仍不用改（配置模型会动，扩展阶段欠账已记录）
- **Alternatives considered**: 接口带策略对象（23 节新十二设想——否决：与 24 代码/002 交付/宪法 VI 冲突）

## 裁决 2：ActionType 四值（修订说明 ②/⑥b）

- **Decision**: `FILE_READ / FILE_WRITE / SHELL_COMMAND / HTTP_REQUEST` 四值；enforce 把 FILE_READ/FILE_WRITE 同路由到 checkFilePath
- **Rationale**: 新版 PDF 正文代码 + 002 已交付 + 技术方案 §6.7 一致；旧三值（FILE_ACCESS/SHELL_EXEC/HTTP_REQUEST）属旧版——新版 PDF 自己的 UML 图仍画旧三值（PDF 内部矛盾），以正文为准
- **Alternatives considered**: 旧三值（否决：三处权威源均为四值）

## 裁决 3：形态机械适配（修订说明 ③）

- **Decision**: 课件 `@Component`/`@Tool` 形态 → 无组件注解纯类 + 装配处显式 `@Bean`（G4-C1）；包名 `io.axion` → `com.axion`；`ToolResult` 返回 → `AxionTool.execute(JsonNode)` 纯实现形态
- **Rationale**: 005 拍板延续（G4-C1：无组件注解，装配处显式 @Bean）；本 feature 与 Spring AI 无关（纯校验类）
- **Alternatives considered**: 课件原样 @Component 组件扫描（否决：005 拍板口径）

## 裁决 4：白名单根构造期 toAbsolutePath（⑦a，P0 修复）

- **Decision**: `allowedRoots` 构造期 `Path.of(...).normalize().toAbsolutePath()`（课件骨架只有 normalize）——相对根按启动目录解析
- **Rationale**: 目标侧 `normalize().toAbsolutePath()` 恒为绝对路径，相对根与绝对 target 的 `startsWith` 永不匹配 → 文件类三工具全拒（确定性缺陷，课件骨架原样带入）；示例配置第一条 `.axion/workspace` 即相对根，照抄必踩
- **Alternatives considered**: 目标不绝对化（否决：相对 target 依赖调用时 cwd，语义漂移）；根与目标都保持相对（否决：工具传入路径形态不可控）

## 裁决 5：Windows 大小写归一（⑦b）

- **Decision**: Windows 下 root/target 字符串 lower-case 归一后比较；Linux 维持大小写敏感；以 `System.getProperty("os.name")` 分支（实现级明确，checkFilePath 内）
- **Rationale**: `Path.startsWith` 大小写敏感而 NTFS 不敏感——Windows 上大小写变体指向同一文件，归一不扩大实际放行面（无安全损失），消除误拒可用性问题；Linux 归一反而会放大放行面（`/SECRET` 误匹配 `/secret` 根），故不归一
- **Alternatives considered**: 统一 lower-case（否决：Linux 放行面变大）；toRealPath 解析（否决：引入 IO 且解析软链改变语义）；文档只注记不改码（否决：开发本机即 Windows，必踩）

## 裁决 6：checkHttpUrl 的 null 防御（修订说明 ⑥d）

- **Decision**: `host != null &&` 显式判空，null → 拒绝（课件骨架无此句，会 NPE）
- **Rationale**: `URI.create("畸形url").getHost()` 可为 null，`host.equals(pattern)` NPE 导致工具执行异常路径而非干净拒绝——补 null→拒绝属实现级明确新增防御
- **Alternatives considered**: 课件骨架原样（否决：NPE 漏放且错误语义是"工具坏了"而非"被拦"）

## 裁决 7：空配置口径 + 构造期 WARN（⑦c）

- **Decision**: 空配置 = 什么都不允许（fail-closed，`Set.copyOf` 空集 contains 恒 false 自然满足）；任一白名单为空构造期打 WARN（配置键名非用户可控值，不违反 NFR-3）
- **Rationale**: 课件 24 §五人工项点名口径「空 = 什么都不允许而非不校验」；WARN 解决"配错无诊断、Agent 反复报不在白名单内"的运维痛点（四维分析稳定性缺口）
- **Alternatives considered**: 空配置 = 不校验（否决：安全底线失守）；WARN 放启动器（否决：装配处只注册，构造器落点最自然且零成本）

## 裁决 8：Shell 首 token 口径（FR-4/⑦d）

- **Decision**: `command.trim().split("\\s+")[0]` 精确匹配——大小写敏感（LS≠ls 拒绝）、前导空格 trim 容忍、`VAR=x cmd` 环境变量前缀按首 token `VAR=x` 拒绝、空命令拒绝
- **Rationale**: 课件骨架无归一化、按精确匹配；harness 表点名「前导空格、大小写变体的处理」；环境变量前缀与空命令按精确匹配自然拒绝（安全方向），文档钉死防被当 bug 报
- **Alternatives considered**: lower-case 归一命令（否决：与课件精确匹配口径不符，且 `LS` 在 Linux 是不同命令）

## 裁决 9：装配替换与 PermissiveSandbox 删除（FR-006/⑦）

- **Decision**: `CliAgentConfiguration.sandbox()` @Bean 返回 WhitelistSandbox（构造注入三 properties）+ 装配类 `@EnableConfigurationProperties` 注册三配置类；`PermissiveSandbox` 类删除（javadoc 已标注"24 节替换后本类删除"）
- **Rationale**: 005 拍板方案 A 的收口；七工具类/Sandbox 接口/ToolExecutor 零改动是上层只认接口的红利兑现（git diff 目检断言）
- **Alternatives considered**: 保留 PermissiveSandbox 供测试（否决：javadoc 承诺删除 + 全放行类留着是安全暗示风险）

## 裁决 10：放后期项（⑦f~i，明确不做，不实现）

- **Decision**: 脚本目录白名单、MCP 工具 enforce 覆盖、违规计数/metrics、白名单热更新——全部留扩展阶段，本节在「明确不做/外部假设」留痕
- **Rationale**: 各自属新增对外概念（新配置键/新改造点）或扩展阶段监控配套，按「后期扩展容易处理」规则延迟；不影响接口与配置模型演进
- **Alternatives considered**: 本节一并实现（否决：克制原则——核心阶段只做第一档，接口墙外不加码）
