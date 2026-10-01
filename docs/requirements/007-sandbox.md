# Sandbox 模块设计文档

> 需求编号：007-sandbox | 对应主体阶段 US-4（Plugin Tool，核心能力四；课件第 23/24 节）
> 文档依据：`docs/AiProgrammingGuide.md` §4.4、`docs/TechnicalSolution.md` §6.7、`docs/DemandAnalysis.md` §5.6/§13、`docs/IndustryResearch.md` §5.6/附录 A；课件第 23 节《Sandbox 原理解析、业界方案与 Axion 设计评审》+ 第 24 节《Sandbox 实现与代码讲解》
>
> 修订说明（2026-09-06）：① **课件口径（用户拍板）**：第 23/24 节新版 PDF 的中文文本最初不可机器提取（嵌入字体无 Unicode 映射，pdftotext 实测 0 中文字符），初稿叙述以旧 .md 回查（D:\code\axion\docs\class\ 第 23/24 节）、24 节代码骨架以新版 PDF ASCII 提取。**当日已解决并完成全文复核**：PyMuPDF 提取成功（先修复本机缺失 msvcp140.dll 导致的 DLL 加载失败）——23 节 6267 汉字、24 节 2246 汉字全文可读，本文档引用与叙述已全部核对到新版 PDF，差异清单见 ⑥。② **ActionType 四值**：旧 .md 的 `FILE_ACCESS/SHELL_EXEC/HTTP_REQUEST` 三值为旧版（新版 PDF 自己的 UML 图也仍画旧三值，与正文代码矛盾，见 ⑥b），以 002 已交付 + 技术方案 §6.7 + 新版 PDF 正文四值（`FILE_READ/FILE_WRITE/SHELL_COMMAND/HTTP_REQUEST`）为准。③ **形态机械适配**（005 拍板延续）：课件 `@Component`/`@Tool` 形态 → 无组件注解纯类 + 装配处显式 `@Bean`（G4-C1）；包名 `io.axion` → `com.axion`；`ToolResult` 返回 → `AxionTool.execute(JsonNode)` 纯实现形态。④ **前序现状红利（2026-09-06 实测）**：七个内置 Tool 的 `execute` 首行 `sandbox.enforce` 已全部就位（005 交付），本节实现层**只新增 WhitelistSandbox + 三配置类 + 装配替换**，工具类零改动。⑤ **图名处置**：技术方案 §6.7 既有图已占用 `docs-sandbox-flow.svg`，本节设计文档图命名 `docs-sandbox-whitelist-flow.svg`（不覆盖技术方案既有图）。⑥ **复核差异清单（新版 PDF 全文复核）**：a. **章节号错位**——新版 PDF 比旧 .md 多出「九、业界选型三类产品」「十、学界共识」两节，旧编号自九起整体 +2（三句话原则 旧九→新十一、接口墙 旧十→新十二、反向套 microVM 旧十一→新十三、分阶段路线 旧十二→新十四、升级信号 旧十三→新十五、路线总览 旧十四→新十六、评审自查 旧十五→新十七），本文档全文引用已改用新版 PDF 编号；b. **新 PDF 内部矛盾一**：24 节 UML 图 ActionType 仍画旧三值、正文代码为四值——以正文四值为准（与 ② 同判）；c. **新 PDF 内部矛盾二**：23 节新十二提出「策略对象带隔离等级」接口形态，但 24 节代码与 002 已交付均为 `enforce(SandboxAction)`（type+target 无策略参数）——以 24 节代码/002 交付为准，本文档 FR-1 同此形态；d. **checkHttpUrl 无 null 防御**：课件骨架 `URI.getHost()` 解析不到会 NPE——本文档 FR-5 补 null→拒绝，属实现级明确新增防御（课件无此句，FR-5 已标注）；e. **课件 24 改造点为「四工具首行加 enforce + 四工具回归」**：Axion 实际 005 已接线七工具，本节改造点为装配替换 + PermissiveSandbox 删除（见 ④），复核确认无其他差异；f. **课件 24 结语 Hermes 教训**（只关 shell ≠ 关住 Agent；MCP 子进程/代码执行为暴露面）→ 已补入「明确不做」；g. 叙述层其余（23 节 §四 劝阻级 / §八 纵深防御 / §十五 升级信号、24 节 §3.2 校验行为 / §四 harness 与两个最值钱回归 / §五 人工项）与本文档一致，无实质差异。⑦ **实施前优化（2026-09-06 四维分析后按「立即 vs 放后期」分类）**：立即——a. FR-3 构造期补 `toAbsolutePath`（相对根与绝对 target 永不匹配的确定性缺陷，课件骨架原样带入，一行修复 + 回归钉）；b. Windows 口径实现级明确（大小写归一、`/workspace` 解析语义注记）；c. 任一白名单为空构造期 WARN；d. 环境变量前缀命令拒绝口径；e. 测试数不写死。放后期（明确不做/外部假设留痕，不实现）——f. 脚本目录白名单（新增配置键小增量）；g. MCP 工具 enforce 覆盖；h. 违规计数/metrics；i. 白名单热更新。

## 背景与价值

Agent 会执行不是自己写的代码和命令——ReAct 循环里模型运行时决定读哪个文件、跑哪条命令、发什么请求，还可能被 prompt injection 诱导去读密钥、外发数据（课件 23 §一）。多租户、多 Agent 共处一个底座（Axion 正是如此）又加了一层要求：A 租户的 Agent 绝不能碰到 B 租户的数据。沙箱因此不是锦上添花，而是 Agent 能上生产的前提——没有隔离，任何有副作用的操作都不敢放行（课件 23 §一）。

隔离强度和开销是跷跷板：应用层白名单校验最轻（零基础设施、零开销），容器隔离居中，microVM（Firecracker/Kata/gVisor）接近容器速度的强隔离，完整 VM/物理隔离最强最贵（课件 23 §三~七）。核心阶段跑的是企业自己配置、相对可信的 Agent，第一档够用；但**文档必须诚实标注：白名单是"劝阻级"防线，防的是模型犯傻误操作，防不住蓄意绕过**（课件 23 §四、技术方案 §6.7 要点一）。

Axion 沙箱的三句话原则（课件 23 §十一）：**方向要想清楚、接口要设计对、实现只做第一档**。接口这道墙（`Sandbox.enforce(SandboxAction)`）002 已经砌好——签名里没有"白名单""容器""镜像"任何一个实现专属词，用最重的 microVM 实现反向套这个签名也能干净套入（课件 23 §十二/§十三、技术方案 §6.7）。005 更进一步把七工具的 `enforce` 调用点全部接好，只留 `PermissiveSandbox` 全放行临时顶着（javadoc 已标注"第 24 节替换后本类删除"）。

本节（第 23/24 节）就是**把墙后面的第一档实现填上**：`WhitelistSandbox` 三层白名单校验，替换全放行，并配套把安全模块的验收标准做成"测绕过"的 harness——测的重点不是"放行对不对"，是"绕得过绕不过"（课件 24 §四）。接入成本小到不成比例（三处配置 + 一行装配替换 + 测试），这正是 23 节"接口设计对了、接入成本小到不成比例"判断的兑现（课件 24 结语）。

## 用户场景

**场景一（本节验收场景）：白名单外的命令被拦、失败可审计**——Agent 调 `shell` 执行模型生成的 `rm` 命令，但白名单只允许 `ls`：Sandbox 抛 `SandboxViolationException`（"命令不在白名单内: rm"），`tool_invocations` 落 `success=false`，`error_message` 人能读懂，模型下一轮看到失败原因从而知道这条路走不通（课件 24 §3.4/§五）。

**场景二：路径穿越被拦**——Agent 读 `/workspace/../../outside/secret.txt` 试图爬到白名单目录之外：`checkFilePath` 经 `normalize().toAbsolutePath()` 标准化后落回白名单外，拒绝（课件 24 §四最值钱回归之一）。

**场景三：形似域名不能绕过通配符**——白名单配置 `*.example.com`：`api.example.com` 放行，但 `evil-example.com` 必须被拒——`endsWith` 实现的经典漏洞（`"evil-example.com".endsWith("example.com")` 为真），匹配必须带点号边界（课件 24 §四最值钱回归之二）。

**场景四：将来升级隔离强度时上层无感**——某天要跑相对不可信代码或多租户，按信号升级到容器/microVM：新增一个实现类、装配处换一行，`Sandbox` 接口、七个 Tool、`ToolExecutor` 审计全部原样（课件 23 §十四/§十五、技术方案 §6.7 升级表）。

## 功能需求

> 从课件第 23/24 节与技术方案 §6.7 提炼：编程指南 §4.4（US-4 任务大类，SandboxChecker 完整版）、需求文档 §5.6（Sandbox 安全隔离）。**交付物列是本节对外概念的白名单**，清单之外的新增对外概念必须停下报告。

| 编号 | 需求 | 交付物（落位模块） | 来源 |
|------|------|-------------------|------|
| FR-1 | **`WhitelistSandbox`（核心阶段唯一实现，implements Sandbox）**：`enforce` 按 `ActionType` 路由四个 case——`FILE_READ`/`FILE_WRITE` 同路由到 `checkFilePath`（技术方案 §6.7 明文：读写分开便于未来分权限，但当前同路由）；`SHELL_COMMAND` → `checkShellCommand`；`HTTP_REQUEST` → `checkHttpUrl`。三个校验方法**全部 private**（外部只看得到 `enforce` 一个入口——三个方法暴露到接口上会把接口带偏成白名单形态，课件 24 §3.2）；任意校验失败抛 `SandboxViolationException`，Tool 执行终止 | `WhitelistSandbox`（axion-tool，com.axion.tool——包名机械适配） | 技术方案 §6.7；课件 24 §3.2 |
| FR-2 | **三块白名单配置**：`FileSandboxProperties`（prefix `file`，`allowedPaths`）、`ShellSandboxProperties`（prefix `shell`，`allowedCommands`）、`HttpSandboxProperties`（prefix `http`，`allowedDomains`）三个 `@ConfigurationProperties` record，装配处 `@EnableConfigurationProperties` 注册（G4-C1 无组件注解，005 拍板延续）；对应 `application.yaml` 配置键 `file.allowed_paths` / `shell.allowed_commands` / `http.allowed_domains`（CLAUDE.md 原则六、技术方案 §6.7、课件 24 §3.2 三处一致）。**空配置 = 什么都不允许，而非"不校验"**（课件 24 §五人工项点名的口径；`Set.copyOf` 空集下 `contains` 恒 false 自然满足，测试钉死）；**任一白名单为空时构造期打 WARN**（启动诊断——配置键名非用户可控值，不违反 NFR-3） | `FileSandboxProperties` / `ShellSandboxProperties` / `HttpSandboxProperties`（axion-tool）+ `application.yaml` 三个配置键 | 课件 24 §3.2/§五；CLAUDE.md 原则六 |
| FR-3 | **`checkFilePath`：路径标准化 + 前缀匹配**——`Path.of(rawPath).normalize().toAbsolutePath()` 后 `allowedRoots.stream().anyMatch(target::startsWith)`；白名单根本身也经 `Path.of(...).normalize().toAbsolutePath()` 预处理（构造期——**相对根按启动目录解析**；根不绝对化则相对根与绝对 target 永不匹配、文件类工具全拒，回归钉死）。**Windows 大小写**：`startsWith` 大小写敏感而 NTFS 不敏感，Windows 下 root/target 字符串 lower-case 归一后比较（实现级明确；NTFS 大小写不敏感故不扩大放行面，Linux 维持大小写敏感）。**`../` 路径穿越由 normalize 吸收**（`/workspace/../../outside/secret.txt` → 白名单外 → 拒绝，最值钱回归测试钉死） | `WhitelistSandbox.checkFilePath`（私有） | 课件 24 §3.2/§四 |
| FR-4 | **`checkShellCommand`：首 token 白名单**——`command.trim().split("\\s+")[0]` 取首 token 精确匹配（**大小写敏感**——LS ≠ ls 拒绝，课件骨架无归一化、按精确匹配实现级明确；**前导空格经 trim 容忍**，课件 harness 表点名的处理；`VAR=x cmd` 环境变量前缀按首 token `VAR=x` 精确匹配拒绝——实现级明确，安全方向） | `WhitelistSandbox.checkShellCommand`（私有） | 课件 24 §3.2/§四；需求文档 §5.6「命令白名单」 |
| FR-5 | **`checkHttpUrl`：host 解析 + 通配符带点号边界**——`URI.create(url).getHost()` 取 host；模式精确匹配（全等）或 `*.` 前缀通配（`host.endsWith(pattern.substring(1))`，即**带点号的 `.example.com` 后缀**——`evil-example.com` 不命中 `*.example.com`，endsWith 经典漏洞的回归钉）；**host 解析不到（null）→ 拒绝**（畸形 URL 不因 NPE 漏放，实现级明确） | `WhitelistSandbox.checkHttpUrl`（私有） | 课件 24 §3.2/§四；技术方案 §6.7 |
| FR-6 | **装配替换（005 交付物改造点）**：`CliAgentConfiguration` 的 `sandbox()` @Bean 从 `PermissiveSandbox` 换成 `WhitelistSandbox`（构造注入三 properties，`@EnableConfigurationProperties` 注册）；**`PermissiveSandbox` 类删除**（其 javadoc 已标注"24 节替换后本类删除"）。七工具类、`Sandbox` 接口、`ToolExecutor` 全部零改动——调用方只认 `Sandbox` 接口的红利兑现 | `CliAgentConfiguration` 改造（axion-cli，003/005 交付物）+ 删除 `PermissiveSandbox`（005 交付物） | 课件 24 §二；005 PermissiveSandbox javadoc |
| FR-7 | **失败路径零新增**：`SandboxViolationException` 是普通 RuntimeException，从 Tool `execute` 抛出后由 `ToolExecutor` 既有 catch 接住（002 已交付），按普通工具失败落 `tool_invocations`：`success=false` + `error_message`（如"命令不在白名单内: rm"）——**不改 ToolExecutor、不新增审计逻辑**；`retryable=true` 为 002 现状行为（RuntimeException 路径），本节维持不改 | —（复用 ToolExecutor 既有路径，审计断言测试承载） | 课件 24 §3.4；002 ToolExecutor |
| NFR-1 | 全程同步阻塞，不引入异步模型；并发由 Java 21 虚拟线程承担（宪法 VII） | — | 宪法 VII；001 NFR-1 延续 |
| NFR-2 | **劝阻级防线诚实标注**：文档明确白名单"防的是模型犯傻误操作、防不住蓄意攻击"，核心阶段不建议用它跑完全不可信代码、不建议对外做多租户（技术方案 §6.7 要点一、课件 23 §四/§十四） | — | 技术方案 §6.7；课件 23 |
| NFR-3 | 结构化 JSON 日志沿用；违规原因（路径/命令/域名）进 `error_message` 审计、**不进日志参数**（用户可控值 CRLF 口径，005 先例） | — | 002 NFR 口径延续 |

### 核心代码骨架（课件 24 节 §3.2 一致，形态机械适配为无组件注解 + com.axion，包名机械适配）

```java
// axion-tool：com.axion.tool —— 三块白名单配置（@ConfigurationProperties record，装配处 @EnableConfigurationProperties 注册）
@ConfigurationProperties(prefix = "file")
public record FileSandboxProperties(List<String> allowedPaths) {}

@ConfigurationProperties(prefix = "shell")
public record ShellSandboxProperties(List<String> allowedCommands) {}

@ConfigurationProperties(prefix = "http")
public record HttpSandboxProperties(List<String> allowedDomains) {}
```

```java
// axion-tool：com.axion.tool —— 唯一实现（课件骨架；G4-C1 无组件注解，装配处显式 @Bean）
public class WhitelistSandbox implements Sandbox {

  private final List<Path> allowedRoots;         // 构造期 normalize + toAbsolutePath（相对根按启动目录解析）
  private final Set<String> allowedCommands;
  private final List<String> allowedDomainPatterns;

  public WhitelistSandbox(FileSandboxProperties fileProps,
                          ShellSandboxProperties shellProps,
                          HttpSandboxProperties httpProps) {
    this.allowedRoots = fileProps.allowedPaths().stream()
        .map(Path::of).map(Path::normalize).map(Path::toAbsolutePath).toList(); // 不绝对化则相对根与绝对 target 永不匹配（⑦a）
    this.allowedCommands = Set.copyOf(shellProps.allowedCommands());
    this.allowedDomainPatterns = List.copyOf(httpProps.allowedDomains());
  }

  @Override
  public void enforce(SandboxAction action) {
    switch (action.type()) {
      case FILE_READ, FILE_WRITE -> checkFilePath(action.target());
      case SHELL_COMMAND -> checkShellCommand(action.target());
      case HTTP_REQUEST -> checkHttpUrl(action.target());
    }
  }

  private void checkFilePath(String rawPath) {
    Path target = Path.of(rawPath).normalize().toAbsolutePath();
    // Windows：root/target lower-case 归一后比较（startsWith 大小写敏感、NTFS 不敏感，⑦b 实现级明确）；Linux 原样比较
    if (allowedRoots.stream().noneMatch(target::startsWith)) {
      throw new SandboxViolationException("路径不在白名单内: " + rawPath);
    }
  }

  private void checkShellCommand(String command) {
    String firstToken = command.trim().split("\\s+")[0];
    if (!allowedCommands.contains(firstToken)) {
      throw new SandboxViolationException("命令不在白名单内: " + firstToken);
    }
  }

  private void checkHttpUrl(String url) {
    String host = URI.create(url).getHost();     // 解析不到（null）→ 拒绝（实现级明确）
    boolean allowed = host != null && allowedDomainPatterns.stream()
        .anyMatch(pattern -> matchesDomain(host, pattern));
    if (!allowed) {
      throw new SandboxViolationException("域名不在白名单内: " + host);
    }
  }

  private boolean matchesDomain(String host, String pattern) {
    if (pattern.startsWith("*.")) {
      return host.endsWith(pattern.substring(1)); // ".example.com" 带点号边界——evil-example.com 不命中
    }
    return host.equals(pattern);
  }
}
```

```yaml
# application.yaml —— 三层白名单（空 = 什么都不允许，不是"不校验"）
file:
  allowed_paths:
    - .axion/workspace
    - /data/reports
shell:
  allowed_commands:
    - ls
    - cat
http:
  allowed_domains:
    - wttr.in
    - api.example.com
```

### 本节交付物清单（Spec-Kit 拆解锚点 / axion-spec 交付清单比对基准）

- **代码**：`WhitelistSandbox`、`FileSandboxProperties`/`ShellSandboxProperties`/`HttpSandboxProperties`（axion-tool）
- **测试**：`WhitelistSandboxTest`（四组：文件路径/Shell 命令/HTTP 域名/接线回归，含两个最值钱回归——`../` 穿越与 `evil-example.com`）；`CliAgentConfigurationTest` 增补 sandbox bean 为 WhitelistSandbox 的装配断言；既有七工具测试 + 005 回归全绿
- **表**：无新增
- **配置**：`file.allowed_paths` / `shell.allowed_commands` / `http.allowed_domains`（application.yaml）
- **改造点**：删除 `PermissiveSandbox`（005 交付物）；`CliAgentConfiguration.sandbox()` @Bean 替换 + `@EnableConfigurationProperties` 注册
- **约定**：空配置 = 全拒绝；劝阻级防线诚实标注；接口中立性自查（思维练习，人工项）

![Sandbox 三层白名单全链路：七工具 execute 首行 enforce 已接线（005）→ WhitelistSandbox 按 ActionType 路由三个私有校验（文件 normalize 穿越拦截 / Shell 首 token / HTTP 点号边界通配）→ 三块配置（file.allowed_paths/shell.allowed_commands/http.allowed_domains，空=全拒绝）→ 放行才真 IO；拒绝抛 SandboxViolationException 复用 ToolExecutor 既有审计（success=false + error_message，零新增）→ 扩展阶段容器/microVM 只换实现类](../../website/public/images/docs-sandbox-whitelist-flow.svg)

## 明确不做

> 来源：课件 23（§四 劝阻级 / §八 纵深防御 / §九 Hermes 教训 / §十三 覆盖面 / §十四~§十五 分阶段与信号）、技术方案 §6.7、需求文档 §5.6、宪法 VI、CLAUDE.md 陷阱表（脚本信任边界）、修订说明 ⑦（四维分析分类）。

- **容器隔离 / microVM（Kata/Firecracker/gVisor）**：扩展阶段按信号驱动升级（信号一：要跑相对不可信代码或多租户；信号二：要跑完全不可信代码或规模化多租户）——接口不变，只新增实现类（课件 23 §十四/§十五）
- **资源占用限制（CPU/内存配额）**：应用层校验做不了资源隔离，归容器档一并解决（课件 23 §二；Shell 超时 005 已交付，属执行超时非资源配额）
- **Profile 级 Tool Policy（allow/deny 策略）**：扩展阶段；核心阶段 Profile.tools 字段限定可用子集已是雏形（技术方案 §6.7 要点二）
- **Java SecurityManager**：JDK 17 废弃、JDK 21 不可用（宪法 VI、需求文档 §5.6 注）
- **防蓄意攻击的绕过扫描增强**（编码绕过、软链接逃逸、命令拼接检测等）：第一档防线明确不覆盖蓄意攻击（课件 23 §四诚实标注；核心阶段靠内网假设 + 审计留痕 + 保守 Profile 纪律兜底，005 口径延续）
- **脚本目录白名单**：`shell` 白名单只查首 token（解释器），脚本路径与脚本内容不受约束——`bash scripts/x.sh` 过首 token 后脚本行为自由（CLAUDE.md 陷阱表口径：shell 跑脚本 = 信任 Agent 作者）；脚本目录校验属新增对外概念（新配置键），归扩展阶段小增量（⑦f）
- **修改 `Sandbox` 接口 / `ToolExecutor` 审计逻辑**：接口墙 002 已定死，失败路径复用既有（课件 24 §一/§3.4）
- **网络出口控制**（数据外泄防线的独立一环）：归扩展阶段配套（课件 23 §八/§十四）
- **违规计数 / 拦截指标聚合**：拦截事件只进 `tool_invocations` 审计（NFR-3 刻意不进日志参数），无计数与 metrics 扩展点——扩展阶段补监控时需动调用点，本节不预留 hook（克制原则，⑦h）
- **MCP 子进程 / 代码执行 / 进程内直调工具的 enforce 覆盖、whole-process 隔离**：课件 24 结语点名的暴露面——「只把 shell 和文件工具关进沙箱，不等于关住了整个 Agent」（Hermes 教训，课件 23 §九/§十三）；核心阶段只在「工具执行」一条路径砌墙，`enforce(SandboxAction)` 抽象为更大覆盖面留位，扩展阶段加实现、扩调用点（课件 24 结语）

## 验收标准

### 自动化部分（harness 承载，`mvn clean verify` 全绿即通过）

安全模块 harness 的特殊性：**测的重点不是"放行对不对"，是"绕得过绕不过"**（课件 24 §四）。`WhitelistSandboxTest` 按三类校验组织，"允许 + 拒绝"成对，再加绕过场景：

| 测试类 | 关键回归点 |
|--------|-----------|
| `WhitelistSandboxTest`（文件路径组） | 白名单内放行 / 白名单外拒绝 / **相对路径穿越被拦**（`/workspace/../../outside/secret.txt` → SandboxViolationException，normalize 回归——课件原文用例；Windows 上该路径解析为当前盘根、断言结果不变，注记平台差异）/ **相对根 + 绝对 target 放行**（根 `.axion/workspace` 构造期 toAbsolutePath 后命中——「根未绝对化」回归钉，⑦a）；白名单根构造期 normalize + toAbsolutePath；Windows 大小写变体放行（平台条件测试，⑦b） |
| `WhitelistSandboxTest`（Shell 命令组） | 白名单内放行（`ls`）/ 白名单外拒绝（`rm`）/ **首 token 前导空格容忍**（`"  ls -la"` → trim 后放行）/ **大小写敏感**（`LS` 拒绝，精确匹配口径）/ `VAR=x ls` 拒绝（环境变量前缀按精确匹配拒，⑦d）；空命令拒绝 |
| `WhitelistSandboxTest`（HTTP 域名组） | 精确匹配放行（`wttr.in`）/ 白名单外拒绝 / **通配符 `*.example.com` 命中 `api.example.com` 但不命中 `evil-example.com`**（点号边界回归——课件原文用例，endsWith 漏洞钉）/ **host 解析不到拒绝**（畸形 URL 不 NPE 不漏放） |
| `WhitelistSandboxTest`（接线回归组） | **空配置 = 全拒绝**（三块全空时三类 action 全部抛异常——"不校验"口径的回归钉）；**枚举四值全覆盖**（FILE_READ/FILE_WRITE 同路由断言） |
| 工具接线回归（各工具既有测试类增补或断言） | `FileTools`/`ShellTools`/`HttpTools`/`NotifyTools` 各一条：白名单外输入被拦且**真正的 IO 没有发生**——mock 底层执行器（进程/HTTP client），`verify(executor, never())`（课件 24 §四：只断言抛异常不够，得证明危险动作真的没跑） |
| `MemoryToolsTest` 同款审计断言 | 违规经 ToolExecutor 执行 → `tool_invocations` 落 `success=false` + `error_message` 含"不在白名单内"（FR-7 零新增审计的机器证据） |
| `CliAgentConfigurationTest` 增补 | `sandbox` bean 为 `WhitelistSandbox` 实例（装配替换断言）；`PermissiveSandbox` 已删除（类不存在） |

最值钱的两个回归测试（课件 24 §四原文，实现之前写最划算——直接决定 matchesDomain 怎么写）：

```java
@Test
void 相对路径穿越必须被拦() {
    // 白名单只有 /workspace，构造 .. 序列爬到白名单之外
    assertThrows(SandboxViolationException.class,
        () -> sandbox.enforce(new SandboxAction(FILE_READ, "/workspace/../../outside/secret.txt")));
}

@Test
void 通配符域名_不能被形似域名绕过() {
    // 白名单：*.example.com
    assertDoesNotThrow(() -> sandbox.enforce(new SandboxAction(HTTP_REQUEST, "https://api.example.com/x")));
    assertThrows(SandboxViolationException.class,   // evil-example.com 以 "example.com" 结尾但不是子域！
        () -> sandbox.enforce(new SandboxAction(HTTP_REQUEST, "https://evil-example.com/x")));
}
```

跑法：`mvn test` 日常全跑；全量 `mvn clean verify` 收尾——**全量全绿，不写死用例数**（用例数随前序节交付动态变化，⑦e）。

### 人工部分（做完怎么验）

- **集成验证（真实链路）**：配一个只允许 `ls` 的白名单（shell.allowed_commands 只留 ls），`axion chat` 让 Agent 跑白名单外命令（如 `rm`）——确认链路上抛了 `SandboxViolationException`、`tool_invocations` 有 `success=false` 记录、`error_message` 人能读懂（课件 24 §五）
- **接口中立性自查（思维练习）**：`Sandbox.enforce(SandboxAction)` 这个签名，换成 `KataMicroVmSandbox` 实现需要加方法吗？不需要才算墙立住了（课件 24 §五；004「接口中立性自查」同款 1 分钟练习）
- **配置边界文档化核对**：白名单配置项为空 = "什么都不允许"而非"不校验"，配置说明与本文档 §FR-2 一致
- **回归**：改造后的七个 Tool 原有测试全绿（含 005 的 AxionToolContractTest/ToolRegistryTest）

## 依赖与假设

### 前序交付物（已就位，本节直接依赖）

- **002-react**：`Sandbox` 接口、`SandboxAction`、`ActionType`（四值）、`SandboxViolationException` 接口墙四件；`ToolExecutor` 失败审计路径（catch RuntimeException → `tool_invocations` success=false + error_message）
- **005-tool**：七工具（ReadFileTool/WriteFileTool/ListDirTool/ShellTools/HttpGetTool/HttpPostTool/NotifyTools）`execute` 首行 `sandbox.enforce(...)` 先于 IO 接线；`PermissiveSandbox` 全放行（javadoc 标注"24 节替换后本类删除"）；CliAgentConfiguration 装配先例
- **003-cli**：`CliAgentConfiguration` 装配（sandbox bean 所在）

**现状确认（2026-09-06 实测）**：接口墙四件 + PermissiveSandbox 在 `axion-tool/com/axion/tool/` 全部就位；七工具 enforce 调用点全部存在（FILE_READ×2/FILE_WRITE×1/SHELL_COMMAND×1/HTTP_REQUEST×3）；`ToolExecutor` 两处 catch（66 行 RuntimeException 失败审计 + 106 行审计写入自身兜底）已就位；`application.yaml` 无 allowed 配置键（本节新增）；`CliAgentConfiguration.sandbox()` 当前返回 PermissiveSandbox（111 行）——与文档描述一致，无缺口。

### 前序缺口（H0 依赖检查）

无——接口墙与接线全部实测就位，本节为纯增量（这是 002"接口先行" + 005"先接线后实现"顺序红利的直接兑现）。

### 改造点（经拍板允许修改的前序公共接口）

- **`PermissiveSandbox` 删除**（005 交付物）：javadoc 已明示"替换后本类删除"，删除后调用方（仅 CliAgentConfiguration 一处）同步替换
- **`CliAgentConfiguration.sandbox()` @Bean 替换**（003/005 交付物）：返回 WhitelistSandbox（构造注入三 properties）+ 装配类 `@EnableConfigurationProperties` 注册三配置类
- 其余前序公共接口零改动：`Sandbox`/`SandboxAction`/`ActionType`/`SandboxViolationException`、七个 Tool 类、`ToolExecutor` 全部原样

### 外部依赖与假设

- **零新第三方依赖**：`java.nio.file.Path`、`java.net.URI`、`String.split` 全部 JDK 原生（课件 24 §3.2）
- **课件口径（用户拍板 2026-09-06）**：23/24 节新版 PDF 中文提取已解决（PyMuPDF + 修复本机缺失 msvcp140.dll），全文复核完成——修订说明 ①⑥ 列明口径与差异清单（章节号错位、PDF 内部两处矛盾、null 防御补强、Hermes 教训补录），复核结论：本文档与新版 PDF 无实质冲突
- **信任边界诚实口径**：应用层白名单是劝阻级防线（防犯傻不防蓄意）；`shell` 跑脚本 = 信任 Agent 作者（CLAUDE.md 陷阱表口径延续）；核心阶段单实例 + 内网假设兜底
- **白名单不可热更新**：三块配置构造期拷贝，改 application.yaml 须重启生效（serve/gateway 常驻模式注意，⑦i——扩展阶段再考虑刷新机制）
- **MCP 挂载风险声明（跨节契约）**：本节白名单不覆盖 MCP 工具（McpToolAdapter 无 enforce，明确不做已列，⑦g）——25/31 节 Demo 挂 MCP server 时，agent 配置与演示文档须显式声明该 MCP 的能力风险边界
- **跨节契约**：本节交付的 `WhitelistSandbox` 与三个配置键是后续节（25 定时任务 Demo 一、31 日报 Demo 三）的白名单消费契约——白名单配置是底座级共享配置，后续节不得改动已验收的校验行为；扩展阶段换容器/microVM 时只新增实现类，本类与配置键继续有效
- **跑通标准**：本节自身无独立 Demo，验收以 harness 全绿 + 集成验证（ls 实跑）为准；"Demo 一/三的 HTTP 调用过域名白名单"（需求文档 §13 原口径）自本节起从"全放行"变为"真实白名单拦截"——25 节起各 Demo 的 agent 配置需把用到的域名/路径/命令列入白名单
