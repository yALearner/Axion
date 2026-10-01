# Tasks: 007-sandbox（Sandbox 三层白名单）

**Input**: Design documents from `specs/007-sandbox/`（plan.md / spec.md / research.md / data-model.md / contracts/ / quickstart.md）

**Prerequisites**: 需求文档 docs/requirements/007-sandbox.md（修订说明 ①~⑦ 已钉死口径——实施必须逐条对照，尤其 ⑦a~e「立即优化」项）

## Format: `[ID] [P?] [Story] Description`（[P] 可并行；文件路径精确到包）

## Phase 1: Setup（前序现状核对）

**Purpose**: H0 依赖检查——确认前序交付物与需求文档「现状确认」一致（机械 grep，不得跳过）

- [X] T001 前序现状核对：grep 确认七工具 execute 首行 `sandbox.enforce` 调用点（FILE_READ×2/FILE_WRITE×1/SHELL_COMMAND×1/HTTP_REQUEST×3）、`CliAgentConfiguration.sandbox()`（axion-cli/src/main/java/com/axion/cli/CliAgentConfiguration.java:111）返回 PermissiveSandbox、`application.yaml`（axion-boot/src/main/resources/application.yaml）无 allowed 配置键、`ToolExecutor` 两处 catch 就位——与需求文档「现状确认（2026-09-06 实测）」逐项一致

## Phase 2: Foundational（三块白名单配置类）

**Purpose**: WhitelistSandbox 的构造依赖；三个 record 互相独立可并行

- [X] T002 [P] 创建 `FileSandboxProperties` 于 axion-tool/src/main/java/com/axion/tool/FileSandboxProperties.java——`@ConfigurationProperties(prefix = "file")` record，`List<String> allowedPaths`；无组件注解（G4-C1，005 拍板延续）；javadoc 注明「空 = 什么都不允许（fail-closed）」
- [X] T003 [P] 创建 `ShellSandboxProperties` 于 axion-tool/src/main/java/com/axion/tool/ShellSandboxProperties.java——`@ConfigurationProperties(prefix = "shell")` record，`List<String> allowedCommands`
- [X] T004 [P] 创建 `HttpSandboxProperties` 于 axion-tool/src/main/java/com/axion/tool/HttpSandboxProperties.java——`@ConfigurationProperties(prefix = "http")` record，`List<String> allowedDomains`

**Checkpoint**: 三配置类就位，编译绿

---

## Phase 3: User Story 1 - 白名单外的命令被拦、失败可审计 (Priority: P1) ★ MVP

**Goal**: WhitelistSandbox 完整实现（构造器 + enforce 四值路由 + 三私有校验）+ Shell 首 token 白名单 + 两个最值钱回归先行 + FR-7 零新增审计机器证据

**Independent Test**: `WhitelistSandboxTest` Shell 命令组 + 两个最值钱回归 + 审计断言测试全绿

### Tests for User Story 1（先行，确保 FAIL 再实现）

- [X] T005 [US1] 创建 `WhitelistSandboxTest` 于 axion-tool/src/test/java/com/axion/tool/WhitelistSandboxTest.java，先写两组：① Shell 命令组——`ls` 放行 / `rm` 拒绝 / `"  ls -la"` trim 容忍放行 / `LS` 拒绝（大小写敏感）/ `VAR=x ls` 拒绝（⑦d）/ 空命令拒绝；② 两个最值钱回归（课件 24 §四原文，实现前写最划算——直接决定 matchesDomain 写法）：`相对路径穿越必须被拦`（白名单 /workspace，`/workspace/../../outside/secret.txt` → 抛 SandboxViolationException）与 `通配符域名_不能被形似域名绕过`（`https://api.example.com/x` 放行、`https://evil-example.com/x` 拒绝）。此时 WhitelistSandbox 不存在——编译失败即 red 证据

### Implementation for User Story 1

- [X] T006 [US1] 创建 `WhitelistSandbox` 于 axion-tool/src/main/java/com/axion/tool/WhitelistSandbox.java（需求文档「核心代码骨架」逐字对照 + ⑦ 项修订）：构造器注入三 properties——`allowedRoots` 构造期 `.map(Path::of).map(Path::normalize).map(Path::toAbsolutePath)`（⑦a：根不绝对化则相对根与绝对 target 永不匹配）；`allowedCommands` = `Set.copyOf`；`allowedDomainPatterns` = `List.copyOf`；**任一为空构造期 log.warn**（⑦c，配置键名非用户可控值）；`enforce` 按 ActionType switch 四值路由（无 default——加枚举值时编译期强制处理），FILE_READ/FILE_WRITE 同路由到 checkFilePath；`checkFilePath` 私有——target `normalize().toAbsolutePath()` + `startsWith` 前缀匹配，**Windows 下 root/target lower-case 归一后比较**（⑦b，`System.getProperty("os.name")` 分支，Linux 原样）；`checkShellCommand` 私有——`trim().split("\\s+")[0]` 首 token 精确匹配（大小写敏感）；`checkHttpUrl` 私有——`URI.create(url).getHost()`，**host null → 拒绝**（⑥d 实现级明确防御，`host != null &&`），`matchesDomain` 带点号边界（`pattern.substring(1)` endsWith）；三个校验方法全部 private；异常 message 照骨架文案（"路径不在白名单内: "/"命令不在白名单内: "/"域名不在白名单内: "）

- [X] T007 [US1] 创建审计断言测试于 axion-tool/src/test/java/com/axion/tool/SandboxAuditTest.java——参照 axion-memory/src/test/java/com/axion/memory/MemoryToolsTest.java 同款模式（`ToolExecutor` + mock `ToolInvocationRepository`，宪法 V 断言）：ShellTools 带只允许 ls 的 WhitelistSandbox 执行 `rm` → `tool_invocations` 落 `success=false` + `error_message` 含「命令不在白名单内」；放行路径落 `success=true`（FR-7 零新增审计的机器证据；与 T005 可并行编写，跑绿需 T006 完成）

**Checkpoint**: T005/T006/T007 全部转绿（实现后），US1 独立可验证

---

## Phase 4: User Story 2 - 文件路径白名单拦路径穿越 (Priority: P1)

**Goal**: 文件路径组行为全量钉死（穿越 + ⑦a 相对根 + ⑦b Windows + 根预处理）

**Independent Test**: `WhitelistSandboxTest` 文件路径组全绿

### Tests for User Story 2

- [X] T008 [US2] `WhitelistSandboxTest` 补文件路径组（axion-tool/src/test/java/com/axion/tool/WhitelistSandboxTest.java）：白名单内放行 / 白名单外拒绝 / **相对根 + 绝对 target 放行**（根 `.axion/workspace` 构造期 toAbsolutePath 后命中——「根未绝对化」回归钉 ⑦a）/ 白名单根构造期 normalize+toAbsolutePath 断言 / **Windows 大小写变体放行**（平台条件测试，`@EnabledOnOs(OS.WINDOWS)`，⑦b）/ 穿越用例 Windows 语义注记（`/workspace` 解析为当前盘根、断言结果不变）。若暴露实现缺口随测即修（预期全绿——T006 已含 ⑦a/⑦b）

---

## Phase 5: User Story 3 - HTTP 域名白名单带点号边界 (Priority: P2)

**Goal**: HTTP 域名组行为全量钉死（精确/通配/null）

**Independent Test**: `WhitelistSandboxTest` HTTP 域名组全绿

### Tests for User Story 3

- [X] T009 [US3] `WhitelistSandboxTest` 补 HTTP 域名组（axion-tool/src/test/java/com/axion/tool/WhitelistSandboxTest.java）：精确匹配放行（`wttr.in`）/ 白名单外拒绝 / **host 解析不到拒绝**（畸形 URL 如 `URI.create("not a url").getHost()==null` 场景 → 拒绝且不 NPE 不漏放，⑥d）。点号边界回归已在 T005 最值钱回归覆盖，本组不重复

---

## Phase 6: User Story 4 - 三块配置装配、空配置全拒绝 (Priority: P2)

**Goal**: 装配替换（PermissiveSandbox → WhitelistSandbox）+ 类删除 + 接线回归组 + 四工具拦截回归

**Independent Test**: `CliAgentConfigurationTest` 装配断言 + 接线回归组 + 工具接线回归全绿；`mvn test` 全量绿

### Tests for User Story 4（先行，装配断言当前 red——sandbox bean 现返回 PermissiveSandbox）

- [X] T010 [US4] `CliAgentConfigurationTest` 增补（axion-cli/src/test/java/com/axion/cli/CliAgentConfigurationTest.java）：① sandbox bean 为 WhitelistSandbox 实例（装配替换断言）；② PermissiveSandbox 类不存在（`Class.forName` 抛 ClassNotFoundException 断言）——当前 red，装配替换后转绿
- [X] T013 [US4] `WhitelistSandboxTest` 补接线回归组（axion-tool/src/test/java/com/axion/tool/WhitelistSandboxTest.java）：**空配置 = 全拒绝**（三块全空时三类 action 全部抛异常——「不校验」口径的回归钉）+ 构造期 WARN 断言（**ListAppender 捕获断言 warn 日志**——006 先例，机器可验，不做弱化为"仅验证不抛"）；**枚举四值全覆盖**（FILE_READ/FILE_WRITE 同路由断言）
- [X] T014 [US4] 工具接线回归（既有工具测试类增补）：`FileTools`/`ShellTools`/`HttpTools`/`NotifyTools` 各一条——白名单外输入被拦且**真正的 IO 没有发生**——mock 底层执行器（进程/HTTP client），`verify(executor, never())`（课件 24 §四：只断言抛异常不够，得证明危险动作真的没跑）。测试构造注入 WhitelistSandbox（带测试白名单）替代原 PermissiveSandbox

### Implementation for User Story 4

- [X] T011 [US4] 装配替换：axion-cli/src/main/java/com/axion/cli/CliAgentConfiguration.java 的 `sandbox()` @Bean 从 `return new PermissiveSandbox()` 改为构造注入三 properties 返回 `new WhitelistSandbox(fileProps, shellProps, httpProps)`；装配类加 `@EnableConfigurationProperties({FileSandboxProperties.class, ShellSandboxProperties.class, HttpSandboxProperties.class})` 注册三配置类
- [X] T012 [US4] 删除 `PermissiveSandbox` 类（axion-tool/src/main/java/com/axion/tool/PermissiveSandbox.java——javadoc 已标注「24 节替换后本类删除」）；**编译暴露的全部引用点同步修正**（既有测试等改注 WhitelistSandbox 测试构造），不得留任何引用

**Checkpoint**: 装配替换完成，PermissiveSandbox 无引用，全量测试绿

---

## Phase 7: Polish（收尾与全量门禁）

**Purpose**: 配置键落位、全量回归、收尾 DoD 证据

- [X] T015 配置键落位：axion-boot/src/main/resources/application.yaml 新增三块白名单配置（需求文档骨架示例：`file.allowed_paths` 含 `.axion/workspace` 与 `/data/reports`、`shell.allowed_commands` 含 `ls`/`cat`、`http.allowed_domains` 含 `wttr.in`/`api.example.com`；注释注明「空 = 什么都不允许」「相对根按启动目录解析」）
- [X] T016 全量门禁：`mvn clean verify` 全绿（**不写死用例数**——用例数随前序节交付动态变化，⑦e；若静态门禁接入则一并全绿）
- [X] T017 收尾 DoD 证据 + 人工项清单：① git diff 目检七工具类、`Sandbox` 接口、`ToolExecutor` 零改动（SC-004 上层零改动）；② **grep 核对 NFR-003**（C1 修复）：违规相关日志输出（warn/error 级别）不得含用户可控值——WARN 只用配置键名、异常 message 只进审计不进日志参数（005 CRLF 口径）；③ 对照 quickstart.md 输出机器判卷结论与**剩余人工项清单**（集成验证 ls 实跑、接口中立性自查、配置边界核对——人工项不自动执行，如实列待办）；④ 按 axion-spec gates.md 收尾 DoD 七项出具证据

---

## Dependencies & Execution Order

### Phase Dependencies

- Setup（Phase 1）→ Foundational（Phase 2）→ US1（Phase 3，含完整实现类）→ US2/US3（Phase 4/5，测试钉行为）→ US4（Phase 6，装配）→ Polish（Phase 7）
- US2/US3 依赖 US1 的 T006（WhitelistSandbox 实现）；US4 依赖 Foundational（三配置类）与 US1（实现类）

### User Story Dependencies

- **US1 (P1)**: 依赖 Foundational——本节核心交付（实现类 + 验收场景），MVP
- **US2 (P1)**: 依赖 US1 T006——补文件路径组行为钉（⑦a/⑦b 回归）
- **US3 (P2)**: 依赖 US1 T006——补 HTTP 组行为钉（null 防御回归）
- **US4 (P2)**: 依赖 US1 + Foundational——装配替换与全拒口径

### Within Each User Story

- 测试先行（T005/T007/T008/T009/T010/T013/T014 测试任务先于或伴随实现任务——宪法质量门 harness 先行，课件 24 §四「实现之前写最划算」）
- 实现随测转绿；红了当场修，不攒到最后（gates.md 任务级 DoD）

### Parallel Opportunities

- T002/T003/T004 三配置类可并行（不同文件）
- T005 与 T007 可并行（不同测试文件；T005 在 T006 前 red、T007 需 T006 后绿——写可并行，跑绿时机不同）
- T013/T014 与 T010 可并行（不同文件）
- T008 与 T009 可并行（同文件不同组——建议顺序执行避免冲突）

## Implementation Strategy

### MVP First（US1 优先）

1. T001 现状核对 → T002~T004 三配置类 → T005 测试先行（red）→ T006 实现 → T007 审计断言 → **STOP and VALIDATE**：`mvn test -pl axion-tool` 全绿
2. US1 即完整沙箱（三校验齐全）——可独立演示「ls 白名单拦 rm」

### Incremental Delivery

1. US1 → 验收场景成立（白名单外命令被拦 + 审计可读）
2. US2 → 穿越与 ⑦a/⑦b 回归钉死（文件类三工具安全底座）
3. US3 → 点号边界与 null 防御钉死（HTTP 类三工具）
4. US4 → 装配替换收口（全放行退出历史）+ 全拒口径
5. Polish → 配置落位 + 全量门禁 + DoD

### Parallel Team Strategy

单人顺序执行即可（feature 规模小）；如多人：A 做 T002~T004，B 写 T005/T007 测试，汇合后 T006 实现。

## Notes

- [P] tasks = 不同文件无依赖；[Story] label 映射 spec.md user story
- 每个 task 开始前：宪法 9 条速查（java-spring-init constitution-checklist.md）+ 停止清单预检（gates.md §停止清单）
- 交付清单白名单：WhitelistSandbox + 三配置类 + 配置键三枚 + PermissiveSandbox 删除——**清单之外的对外概念一律先停**（停止清单第 1 条）
- 已定字面量逐字保真：异常 message 文案、配置键名、ActionType 四值（停止清单第 2 条）
- 测试方法名英文（gates.md H1/H5）
- 不自动 commit / push / 运行 package.sh——同步时机由用户决定
