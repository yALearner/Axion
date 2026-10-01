# Tasks: 011-plugin-agent 插件化 Agent

**Input**: Design documents from `/specs/011-plugin-agent/`（plan / spec / research R1~R10 / data-model / contracts / quickstart）

**Prerequisites**: plan.md + spec.md（6 US：US1~4 P1、US5~6 P2）+ data-model.md（L3 判定状态机 §四）+ contracts/agent-runtime.md

**Tests**: 本节 harness 是交付主体（课件 §三验收清单）——测试任务与实现任务同 phase 伴随落地（harness 先行：测试定义断言 → 实现 → 跑绿）。

**审计零新表说明**：本节不新增审计面——read_file/shell 走既有 ToolExecutor 审计路径（宪法 V 不变量 ②），T019/T023 含 tool_invocations 成败落账断言。

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 可并行（不同文件、无未完成依赖）
- **[Story]**: 所属 user story

---

## Phase 1: Setup（共享 fixture）

**Purpose**: 示例 Agent 测试资源——US1/US3/US5 的 fixture（junction 不可提交 git，绑定由测试程序创建，research R9）

- [X] T001  [P] 创建示例 Agent 测试资源：`axion-core/src/test/resources/agents/daily-reconcile/AGENT.md`（frontmatter 无 notify_channels 无 skills 字段、正文四步按拍板②③渠道按名引用）、`REFERENCE.md`（字段字典/已知差异）、`scripts/reconcile.py`（纯标准库无 key，按设计文档全文）
- [X] T002  [P] 创建公共技能实体 fixture：`axion-core/src/test/resources/skills/report-format/SKILL.md`（frontmatter name/description + 报告规范 P0/P1/P2 分级，按设计文档全文）

## Phase 2: US1 — AgentLoader 目录解析与派生（Priority: P1）

**Goal**: ProfileLoader 更名 AgentLoader 并扩职责（拆 frontmatter/正文 + 认资源 + name=目录名校验）；坑一/坑二/坑八回归
**Independent Test**: `AgentLoaderTest` + `DeriveProfileTest`（core 单测、@TempDir fixture、无 Spring 无网络）

- [X] T003  [US1] 更名 `ProfileLoader` → `AgentLoader`：`axion-core/src/main/java/com/axion/core/AgentLoader.java`（类名 + javadoc 更新）；`axion-cli/src/main/java/com/axion/cli/CliAgentConfiguration.java` 引用同步；`ProfileLoaderTest.java` → `AgentLoaderTest.java` 更名
- [X] T004  [US1] `AgentLoader.split(String)` 拆 frontmatter（YAML Map）+ 正文（原样文本）；`loadBody(Path agentDir)` 现读正文去 frontmatter（缺 AGENT.md 报错点名）；`detectResources(Path agentDir)` 返回存在的 {scripts, skills, REFERENCE.md} 集合——同一解析器复用（坑一）
- [X] T005  [US1] `AgentLoader.deriveProfile` 增校验：frontmatter `name` 与目录名不一致 → IllegalArgumentException 点名（坑八）；既有缺 name/provider 报错点名保持（坑二）；schedules 缺 id 报错保持（010 ⑦c）
- [X] T006  [US1] `AgentLoaderTest` 扩展：拆正文原样无二次加工（坑一回归）；认资源三样（T001 fixture）；缺 name/provider 报错点名文件与键（坑二回归）；name≠目录名报错（坑八回归）；schedules 缺 id（010 延续）
- [X] T007  [US1] `DeriveProfileTest`：frontmatter 全字段映射 Profile；schedules 原样带进派生 Profile（定时来自 Agent 的直接证据）；provider 引用校验（001 口径）
- [X] T008  [US1] 跑 `mvn -pl axion-core -am test -Dtest='AgentLoaderTest,DeriveProfileTest'` 全绿

## Phase 3: US2 — 注册就位（Priority: P1）

**Goal**: ProfileRegistry 补 remove/exists；AgentScheduler 抽 registerProfile（句柄表/登记/registrations）；启动扫描与运行时注册同一段代码（坑五）
**Independent Test**: `ProfileRegistryRuntimeTest` + `AgentSchedulerRegisterTest` + `AgentScanRegisterTest`（core 单测，真实 ThreadPoolTaskScheduler + mock store，AgentSchedulerTest 既有模式）

- [X] T009 [US2] `ProfileRegistryRuntimeTest`：register 后立即 findByName 可见；remove/exists 语义（幂等注销）；非法配置经 deriveProfile 报错与启动扫描路径**同一异常类型 + 同一消息**（坑五回归）
- [X] T010 [US2] `AgentSchedulerRegisterTest`：registerProfile 后 scheduledTasks/registrations 有条目、store.register 被调（含 next_run_at）；cron/zone 来自 Profile.schedules；同 id 冲突报错指明双方 Profile（010 文案）
- [X] T011 [US2] `ProfileRegistry` 补 `remove(String)`（幂等）+ `exists(String)`：`axion-core/src/main/java/com/axion/core/ProfileRegistry.java`
- [X] T012 [US2] `AgentScheduler.registerProfile(Profile)`：registerAll 循环体抽出（id 冲突查 registrations、报错文案同 010 → store.register → taskScheduler.schedule → scheduledTasks + registrations）；registerAll = 遍历 registry.list() 调 registerProfile：`axion-core/src/main/java/com/axion/core/AgentScheduler.java`
- [X] T013 [US2] `AgentScanRegisterTest`：放 N 个 Agent 目录（含 T001 fixture）→ ProfileRegistry 出现 N 个、带 schedules 的进 AgentScheduler（scheduledTasks 有条目）；坏目录跳过不阻断（003 口径）
- [X] T014 [US2] 跑 `mvn -pl axion-core -am test -Dtest='ProfileRegistryRuntimeTest,AgentSchedulerRegisterTest,AgentScanRegisterTest'` 全绿

## Phase 4: US3 — 上下文注入（Priority: P1）

**Goal**: ContextLoader 注入正文（现读无缓存）+ Skill 元数据改绑定路径；FILE_READ 动态根 = 当前 Agent 目录（坑三/坑四/坑七 + 修订说明 ⑦）
**Independent Test**: `ProgressiveDisclosureTest`（core 单测 + @TempDir + 程序建 junction，ContextLoaderTest 既有模式）

- [X] T015 [US3] `ProgressiveDisclosureTest`（正文/绑定路径部分，S5 F1 修正落位：`axion-tool/src/test/java/com/axion/tool/ProgressiveDisclosureTest.java`——消费 core 的 PromptBuilder/ContextLoader + tool 沙箱类）：PromptBuilder 产物含正文不含 frontmatter；改正文下一次 load 即新正文（坑三回归）；AGENT.md 缺失报错（坑四回归）；元数据注入**绑定路径** `<agentDir>/skills/<name>/SKILL.md` 而非实体真实路径（修订说明 ⑦）；REFERENCE.md 内容与脚本代码不进 prompt
- [X] T016 [US3] `ContextLoader.load` 注入正文（现读去 frontmatter、无缓存）+ AGENT.md 缺失报错 + Skill 元数据改绑定路径（绑定目标校验保留：位于 `.axion/skills/` 内、逃逸报错）：`axion-core/src/main/java/com/axion/core/ContextLoader.java`
- [X] T017 [US3] `WhitelistSandbox` FILE_READ 动态根（构造器增 workspaceRoot；FILE_READ = 静态 allowedRoots ∪ 当前 Agent 目录经 ProfileContext、无上下文静态兜底；FILE_WRITE 不动）：`axion-tool/src/main/java/com/axion/tool/WhitelistSandbox.java`
- [X] T018 [US3] `ProgressiveDisclosureTest` 增补（同 T015 文件，axion-tool）：绑定路径 read_file 通过动态白名单、他 Agent REFERENCE.md 与未绑定实体路径被拒（坑七回归）；read_file/shell 经 ToolExecutor 落 tool_invocations 成败都记（宪法 V 审计零改动核对）
- [X] T019 [US3] 跑 `mvn -pl axion-core,axion-tool -am test -Dtest='ProgressiveDisclosureTest,ContextLoaderTest,WhitelistSandboxTest'` 全绿（ContextLoaderTest/WhitelistSandboxTest 为前序回归）

## Phase 5: US4 — L3 脚本沙箱（Priority: P1）

**Goal**: 新配置键 shell.allowed_interpreters 双白名单 + 简单形态钉死（脚本参数透传）+ scripts/ 目录限定 + cwd=Agent 目录（坑九/坑六/修订说明 ⑥，data-model §四状态机）
**Independent Test**: `WhitelistSandboxTest` 扩展 + `ProgressiveDisclosureTest` 解释器部分（tool 单测）

- [X] T020 [US4] `WhitelistSandboxTest` 扩展：双白名单命中语义；`allowed_interpreters` 声明了未进 `allowed_commands` 的项构造期 WARN（永不命中、消息只含配置键名）
- [X] T021 [US4] `ShellSandboxProperties` 增 `List<String> allowedInterpreters` 字段：`axion-tool/src/main/java/com/axion/tool/ShellSandboxProperties.java`
- [X] T022 [US4] `WhitelistSandbox.checkShellCommand` 解释器分支（data-model §四状态机 1~7 步）：双白名单命中才进 L3 校验；无 Agent 上下文拒绝（fail-closed）；简单形态——token[1] 必须 scripts/ 下相对路径（绝对化后落本 Agent scripts/、Windows 大小写不敏感 007 ⑦b）、token[2..] 脚本参数透传；引号/元字符/脚本路径前选项/裸解释器一律拒绝（坑九）：`axion-tool/src/main/java/com/axion/tool/WhitelistSandbox.java`
- [X] T023 [US4] `ShellTools` 构造器增**解释器集合 + workspaceRoot**（S5 C1 修正：agentDir = workspaceRoot/agents/<name>，ProfileContext 取 name，research R7）；execute 时首 token ∈ 解释器集合 → ProcessBuilder.directory(agentDir)（坑六），非解释器命令行为不变：`axion-tool/src/main/java/com/axion/tool/builtin/ShellTools.java`
- [X] T024 [US4] `ProgressiveDisclosureTest` 增补（解释器部分，同 T015 文件 + `axion-tool/src/test/java/com/axion/tool/builtin/ShellToolsTest.java` 扩展坑六 cwd）：简单形态放行（含脚本参数透传）；链式 `python scripts/x.py ; curl ...` 拒、`bash -c '...'` 拒、脚本路径前选项拒（坑九回归）；他 Agent scripts/ 与任意路径拒；无 Agent 上下文拒（⑥）；ShellTools cwd=Agent 目录（坑六回归）
- [X] T025 [US4] 跑 `mvn -pl axion-tool -am test -Dtest='WhitelistSandboxTest,ShellToolsTest,ProgressiveDisclosureTest'` 全绿

## Phase 6: US5 — daily-reconcile 示例 Agent（Priority: P2）

**Goal**: fixture 与设计文档全文逐字一致（拍板②③口径）；被 US1/US3 测试消费的断言到位
**Independent Test**: T026 派生断言 + 人工项（quickstart 人工 1/2）

- [X] T026 [US5] fixture 与设计文档全文逐字核对（T001/T002 产物）：AGENT.md 正文四步、tools/schedules/provider、reconcile.py 输出 JSON 形状、report-format 分级规则——与 `docs/requirements/011-plugin-agent.md` §1.3~1.4 一致
- [X] T027 [US5] `AgentScanRegisterTest` 用 daily-reconcile fixture 派生断言：Profile.tools/schedules/provider 与 fixture frontmatter 全对

## Phase 7: US6 — 装配接线与 Demo 前置（Priority: P2）

**Goal**: CliAgentConfiguration 适配新构造参数；application.yaml 配置落地；存量 Agent 去重（人工项）
**Independent Test**: 全量 `mvn clean verify`（T031）+ 人工项（quickstart 人工 5）

- [X] T028 [US6] `CliAgentConfiguration` 适配：profileRegistry Bean 用 AgentLoader（T003 已更名）；`WhitelistSandbox` 构造增 workspaceRoot（Path.of(".axion")）；`ShellTools` 构造增**解释器集合（从 shellProps.allowedInterpreters()）+ workspaceRoot**（S5 C1 修正）：`axion-cli/src/main/java/com/axion/cli/CliAgentConfiguration.java`
- [X] T029 [US6] `application.yaml`：`shell.allowed_commands` 增补 `python`/`bash`（⑨：010 FR-7 到期）；新键 `shell.allowed_interpreters: [python, bash]`（③ 拍板）——注释对齐修订说明 ⑥/⑨ 双白名单与子集 WARN 语义：`axion-boot/src/main/resources/application.yaml`
- [X] T030 [US6] 存量工作区 Agent 去重（人工项，运行时 `.axion/` 文件不提交）：weather/default 的 identity.prompt 留人格、正文留任务步骤（修订说明 ⑩）

## Phase 8: Polish（收尾）

**Purpose**: 全量门禁 + 工程化沉淀

- [X] T031 全量 `mvn clean verify`（10 模块 + Spotless/P3C/SpotBugs/FindSecBugs/PMD）全绿——含前序 001~010 全部测试回归（DoD 1/4）
- [X] T032 实施实录沉淀：CLAUDE.md 陷阱表补录本节踩坑（如有）+ `docs/reviews/011-plugin-agent-review.md` 代码 review 指南（010 模式）+ flow-status.md S6 收尾更新

---

## Dependencies

```
Phase 1 (T001~T002, [P])
  → Phase 2 US1 (T003→T004→T005; T006/T007 伴随; T008 收口)
    → Phase 3 US2 (T009/T010 先行 → T011/T012 实现 → T013 → T014)
      → Phase 4 US3 (T015 先行 → T016/T017 → T018 → T019)
        → Phase 5 US4 (T020 先行 → T021/T022/T023 → T024 → T025)
          → Phase 6 US5 (T026 → T027)
            → Phase 7 US6 (T028/T029/T030，T030 人工)
              → Phase 8 (T031 → T032)
```

US3 与 US4 串行（同一 WhitelistSandbox 文件，避免编辑冲突）；US6 依赖 US1~US4 全部。

## Parallel Examples

- Phase 1：T001 与 T002 并行（不同文件）
- Phase 6：T026/T027 与 Phase 7 的 T029（application.yaml 独立文件）可并行
- 全流程 [P] 标记共 3 处（T001/T002 不同 fixture 文件）

## Implementation Strategy（MVP 先行）

1. **MVP = US1+US2**（T003~T014）：目录 → 派生 → 注册 → 定时句柄——"一个目录定义一个 Agent"机制成立
2. **增量 1 = US3**（T015~T019）：正文注入 + 渐进式披露守点——"会自己说对的话"
3. **增量 2 = US4**（T020~T025）：L3 脚本沙箱——"会安全地跑脚本"
4. **增量 3 = US5+US6**（T026~T030）：示例 Agent + 装配收口——"到点自己跑"可演示
5. **收尾 = Phase 8**：全量门禁 + 工程化沉淀
