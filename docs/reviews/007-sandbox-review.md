# 007-sandbox 代码 Review 指南

> 生成：2026-09-07（交付后复盘 + 人工验收四项闭环后）。
> 复盘全记录见 `specs/007-sandbox/flow-status.md`（含 S0 分支拍板、G2/G4 门禁处置、S5 三 WARNING 修复、SpotBugs 3 项 EI 抑制、ErrorProne StringSplitter 处置），本指南是 review 导航。

## 一、全景：工具调用 → 三层白名单 → 审计

```
Agent 调七个内置 Tool（005 已接线，本节零改动）
  → 各 Tool.execute 首行 sandbox.enforce(SandboxAction(type, target))（坑十：enforce 先于 IO）
  → WhitelistSandbox（核心阶段唯一实现，接口墙宪法 VI 的第一档）：
      enforce 按 ActionType 路由三个 private 校验（无 default——枚举加值编译期强制处理）
      FILE_READ/FILE_WRITE → checkFilePath（同路由，读写共用路径白名单）
      SHELL_COMMAND        → checkShellCommand（首 token 精确匹配）
      HTTP_REQUEST         → checkHttpUrl（精确/点号边界通配/null 防御）
  → 白名单来自三块 @ConfigurationProperties record（file.allowed_paths / shell.allowed_commands /
      http.allowed_domains，装配处 @EnableConfigurationProperties 注册；空 = 全拒绝 fail-closed）
  → 放行 → 真正 IO；拒绝 → 抛 SandboxViolationException（message 人可读）
  → ToolExecutor 既有 catch（002 已交付）落 tool_invocations：success=false + error_message——零新增审计逻辑
```

行为契约由 `WhitelistSandboxTest` 四组（Shell 命令/文件路径/HTTP 域名/接线回归，20 用例）+ `SandboxAuditTest`（2 用例）+ 四工具真实白名单接线回归各 1 条钉死。

## 二、逐文件梳理

### axion-tool/com/axion/tool（4 新增 + 1 删除，主代码零改动其余）

| 文件 | 关键点 |
|------|--------|
| `WhitelistSandbox` | 唯一实现：构造期三份不可变拷贝（`toAbsolutePath` ⑦a / `Set.copyOf` / `List.copyOf`）+ 任一为空 WARN（⑦c，消息只含配置键名）；`startsWithRoot` Windows lower-case 归一（⑦b，保持 `Path.startsWith` 逐段语义——注释明确不可退化字符串前缀匹配）；`checkHttpUrl` host null 防御（⑥d，课件骨架无此句会 NPE）；`checkShellCommand` `split("\\s+", 2)[0]`（ErrorProne StringSplitter 门禁要求显式 limit，首 token 语义与骨架一致）；无状态不可变 → 虚拟线程并发零竞争 |
| `FileSandboxProperties` / `ShellSandboxProperties` / `HttpSandboxProperties` | 三 record（prefix file/shell/http）+ 紧凑构造器 null→空归一（构造器绑定缺失配置键给 null 时不 NPE、保持 fail-closed）+ EI_EXPOSE_REP 抑制（record 访问器是绑定契约要求，构造期 copyOf 后不保留引用——004/006 先例） |
| `PermissiveSandbox`（删除） | 005 拍板方案 A 全放行占位——javadoc 承诺「24 节替换后本类删除」兑现；编译暴露引用点仅 CliAgentConfiguration 一处，已同步替换 |

### axion-cli（1 改造）

| 文件 | 关键点 |
|------|--------|
| `CliAgentConfiguration` | `sandbox()` @Bean 改为构造注入三 properties 返回 WhitelistSandbox；装配类加 `@EnableConfigurationProperties` 注册三配置类（G4-C1 无组件注解口径）；类 javadoc「PermissiveSandbox 临时接线」措辞同步更新 |

### axion-boot（1 配置）

| 文件 | 关键点 |
|------|--------|
| `application.yaml` | 三块白名单配置键 + 四条口径注释（fail-closed / 相对根按启动目录解析 / 不可热更新 / 劝阻级诚实标注） |

### 测试（6 文件）

| 文件 | 关键点 |
|------|--------|
| `WhitelistSandboxTest` | 四组 20 用例：Shell 组（放行/拒绝/前导空格/大小写/环境变量前缀/空命令）+ 两个最值钱回归（`../` 穿越、`evil-example.com` 点号边界——课件 24 §四原文，实现前写）+ 文件组（含 ⑦a 相对根回归钉、根构造期 normalize、⑦b Windows 平台条件测试）+ HTTP 组（精确/白名单外/null host/无 authority）+ 接线回归组（空配置全拒、ListAppender WARN 断言、枚举四值同路由） |
| `SandboxAuditTest` | 006 MemoryToolsTest 同款：真实 ShellTools + 真实 WhitelistSandbox 经 ToolExecutor 执行——违规落 `success=false` + `error_message` 含「命令不在白名单内: rm」、放行落 `success=true`（FR-7 零新增审计机器证据） |
| `FileToolsTest` / `ShellToolsTest` / `HttpToolsTest` / `NotifyToolsTest` 各增 1 条 | 真实 WhitelistSandbox（非 mock 拒绝）白名单外输入——被拦且真 IO 没发生（文件未创建 / 副作用文件不存在 / MockWebServer 零请求 / adapter never 调用） |

## 三、重点 review 清单（按风险排序）

1. **⑦a 根绝对化**（`WhitelistSandbox.java` 构造器）：`Path.of(...).normalize().toAbsolutePath()` 三连——删掉 `toAbsolutePath` 则相对根与绝对 target 永不匹配、文件类三工具全拒（P0 缺陷，课件骨架原样带入，本文档修正）；回归钉 = `relativeRootMatchesAbsoluteTarget`
2. **⑦b Windows 大小写归一**（`startsWithRoot`）：刻意保持 `Path.startsWith` 逐段语义——注释点明不可退化为 `String.startsWith`（`d:\workspace2` 会误命中根 `d:\workspace`）；平台条件测试 `@EnabledOnOs(OS.WINDOWS)`，Linux 维持大小写敏感
3. **⑥d host null 防御**（`checkHttpUrl`）：`host != null &&`——课件骨架无此句，畸形/无 authority URI 会 NPE 而非干净拒绝；回归钉 = `httpRejectsUnresolvableHost` + `httpRejectsUriWithoutAuthority`（file:/etc/passwd 借 HTTP 工具读本地文件被拦）
4. **三 record null→空归一**（紧凑构造器）：Spring Boot 构造器绑定缺失配置键给 null——不归一则启动 NPE、违背 FR-2「空 = 全拒绝」；这是文档未点名、实现必须有的内部补充（偏差排查已确认，非对外概念）
5. **`split("\\s+", 2)` 与骨架字面量差异**：需求文档骨架写 `split("\\s+")[0]`，实现加 `limit=2` 满足 ErrorProne StringSplitter 门禁——首 token 语义恒等（limit 只影响返回数组长度），注释已说明
6. **装配替换**（`CliAgentConfiguration.java`）：`@EnableConfigurationProperties` 注册 + 构造注入三 properties——bean 定义唯一改动点；`CliAgentConfigurationTest` 两条断言（bean 类型 + PermissiveSandbox 类不存在）钉死
7. **审计复用零新增**（`SandboxAuditTest`）：违规走 002 既有 RuntimeException catch——本节没有动 ToolExecutor 一行，机器证据 = 审计落账断言而非新增代码
8. **fail-closed 三连**（空配置全拒 + WARN + 接线回归组）：空 = 什么都不允许不是"不校验"；WARN 消息只含配置键名（NFR-3 用户可控值不进日志）

## 四、刻意留白（review 时不要当成缺陷报）

1. **MCP 工具 enforce 覆盖**：McpToolAdapter 无沙箱——明确不做（Hermes 教训，课件 24 结语）；挂 MCP 的 agent 须显式声明风险边界（跨节契约）
2. **脚本目录白名单**：shell 只查首 token（解释器），脚本路径/内容不受约束——信任 Agent 作者口径（CLAUDE.md 陷阱表）；扩展阶段小增量
3. **违规计数/metrics**：拦截事件只进 tool_invocations 审计，无计数聚合——NFR-3 刻意不进日志参数，扩展阶段补监控
4. **白名单热更新**：构造期拷贝不可变——改配置须重启（serve/gateway 常驻模式运维心智）
5. **软链逃逸 / TOCTOU / DNS rebinding**：normalize 不解析软链、host 校验与真实 DNS 解析分离——劝阻级防线诚实标注范围（防犯傻不防蓄意）
6. **字符串前缀匹配性能**：白名单规模个位数，流式扫描无优化必要；`split` 正则每调编译的微优化不做（7 位数量级纳秒）
7. **Windows 路径大小写之外的平台差异**：`/workspace` 类 Unix 绝对路径在 Windows 解析为当前盘根——穿越测试断言结果平台不变，注记即可
8. **Shell 大小写不归一**：与文件路径（Windows 归一）不对称是刻意的——`LS` 在 Linux 是不同命令，命令精确匹配口径（FR-4）

## 五、建议 review 顺序

1. `WhitelistSandbox`（先看懂接口墙第一档：enforce 路由 → 三私有校验 → ⑦a/b/c 与 ⑥d 四处实现级明确）
2. 三配置 record（null→空归一 + EI 抑制理由）+ `WhitelistSandboxTest`（四组回归钉）
3. `SandboxAuditTest` + 四工具接线回归各 1 条（真 IO 零发生证据）
4. `CliAgentConfiguration`（装配替换唯一改动点）+ `CliAgentConfigurationTest`（装配断言）
5. `application.yaml`（三配置键 + 四条口径注释）

## 六、当前验收状态

- **人工验收四项全部闭环**（2026-09-07）：
  1. 集成验证（真实装配链路）——临时 harness `SandboxManualIT`（验收后删除）实拍四段证据：`success=false` + `errorMessage=命令不在白名单内: rm` + 真实 SQLite `tool_invocations` 落 1 行（shell/success=false/同文案）+ 断言全绿
  2. 接口中立性自查（用户思维练习）——三问全对：microVM 套 `enforce(SandboxAction)` 不需要加方法 / 签名无「白名单/容器/镜像」字样 / target 对 microVM 有意义——墙立住了（004 遗留同款练习本节完成）
  3. 配置边界核对——四条口径注释齐全（fail-closed / 相对根按启动目录解析 / 不可热更新 / 劝阻级诚实标注）
  4. 上层零改动目检——git diff 空输出（七工具主代码、接口墙四件、ToolExecutor 逐字节未动）
- `mvn clean verify` 全绿（storage 11 + core 44 + provider 6 + memory 43 + tool 86 + cli 8 + boot 1）+ 全静态门禁（Spotless / SpotBugs / ErrorProne）；SpotBugs 3 项 EI_EXPOSE_REP 按 004/006 先例抑制；ErrorProne StringSplitter 用 `split(regex, 2)` 满足
- **剩余待办（如实记录）**：无实质遗留——006 的真实 Mem0 实例验证待办延续；MCP/脚本目录/metrics/热更新等归扩展阶段（明确不做已列）
