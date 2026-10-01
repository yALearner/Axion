# Feature Specification: 插件化 Agent（一个目录定义一个会自己跑的 Agent）

**Feature Branch**: `011-plugin-agent`

**Created**: 2026-09-13

**Status**: Draft

**Input**: 需求文档 docs/requirements/011-plugin-agent.md（课件第 29 节：插件化 Agent；修订说明 ①~⑩ 口径全钉——宪法 IV 软连接拍板、004 拍板全局注册表、shell.allowed_interpreters ③ 拍板配置化、简单形态坑九、name=目录名坑八、三笔 30 节债）

## Clarifications

### Session 2026-09-13

- Q: 解释器命令「简单形态」（坑九）下，脚本路径之后是否允许带脚本参数（如 `python scripts/x.py --date 2026-09-12`）？ → A: 允许——token[2..] 作为脚本参数透传；元字符/引号/链式仍全命令拒绝（坑九防线不变；安全边界是脚本路径本身，脚本 = 信任的代码）

## User Scenarios & Testing *(mandatory)*

### User Story 1 - AgentLoader 目录解析与派生（Priority: P1）

`ProfileLoader` 更名 `AgentLoader` 并扩职责：读 `AGENT.md` 拆 frontmatter（配置）与正文（指令，原样、不做二次加工——坑一：拆分与派生同一解析器）；认出 `scripts/`、`skills/`、`REFERENCE.md` 资源所在；`deriveProfile` 映射 `Profile`（缺 name/provider 报错点名文件与键——坑二；**frontmatter name 与目录名不一致报错——坑八**；schedules 含 id 校验，010 ⑦c）；`loadAll` 扫描（坏目录记错误日志跳过不阻断启动，003 口径）。

**Why this priority**: "定义一个 Agent"的机制本体——扫描 → 派生 → 注册三步的第一步；派生 Profile 是零改动复用整台底座的前提（课件 §2.1）。

**Independent Test**: `AgentLoaderTest` + `DeriveProfileTest`（core 单测、临时目录 fixture、无 Spring 无网络）：拆正文原样、资源认出、缺 name/provider/name≠目录名报错点名、schedules 原样带进派生 Profile。

**Acceptance Scenarios**:

1. **Given** 含 frontmatter + 正文 + REFERENCE.md + scripts/ + skills/ 的 Agent 目录，**When** 解析，**Then** frontmatter 与正文正确拆分、正文原样无二次加工（坑一）；资源认出 scripts//skills//REFERENCE.md
2. **Given** frontmatter 缺 name 或 provider 段，**When** deriveProfile，**Then** 报错且点名文件与缺失键（坑二）
3. **Given** frontmatter `name` 与目录名不一致，**When** deriveProfile，**Then** 报错点名（坑八：不校验则注册成功、首次触发才炸）
4. **Given** schedules 条目缺 id，**When** 解析，**Then** 报错（010 ⑦c 延续）
5. **Given** 合法 frontmatter，**When** deriveProfile，**Then** Profile 全字段映射正确、schedules 原样带进（定时来自 Agent 的直接证据）
6. **Given** N 个目录含 1 个坏目录，**When** loadAll，**Then** 好目录全部派生、坏目录记错误日志跳过不阻断（003 口径）

---

### User Story 2 - 注册就位：运行时注册 + 定时句柄（Priority: P1）

`ProfileRegistry` 补 `remove(String)`/`exists(String)`（register 已有）；`AgentScheduler.registerAll` 循环体抽成 `registerProfile(Profile)`（登记 `scheduled_tasks`、按 Profile.Schedule 的 cron/zone 注册触发、句柄入 `scheduledTasks`、注册信息入 `registrations`；id 冲突查 `registrations`、报错文案与 010 一致）。运行时新增与启动扫描走**同一段 deriveProfile + register**——同一异常类型、同一消息（坑五）。

**Why this priority**: 为 30 节"API 上传 = 丢目录"铺路的三件前置之二；同一段注册代码是"两条来源同规矩"的机器证据（课件 §2.4）。

**Independent Test**: `ProfileRegistryRuntimeTest` + `AgentSchedulerRegisterTest` + `AgentScanRegisterTest`（core 单测，真实 ThreadPoolTaskScheduler + mock store，008/010 harness 模式）：register 后 get 立即可见、remove/exists 语义、非法配置与启动路径同一异常同一消息；registerProfile 后句柄表有条目、cron/zone 来自 Profile.schedules；N 目录 → 注册表 N 个、带 schedules 的进调度器。

**Acceptance Scenarios**:

1. **Given** 一个派生出的 Profile，**When** register，**Then** 立即 findByName 可见；exists=true；remove 后 findByName 为空、exists=false（幂等注销）
2. **Given** 非法配置的 Agent 目录，**When** 运行时注册路径（deriveProfile + register）处理，**Then** 报错与启动扫描路径**同一异常类型 + 同一消息**（坑五）
3. **Given** 带 schedules 的 Profile，**When** registerProfile，**Then** scheduledTasks/registrations 有条目、scheduled_tasks 已登记（含 next_run_at）、cron/zone 与 Profile.schedules 一致
4. **Given** 两个 Profile 的 schedule 同 id，**When** 注册，**Then** 报错指明冲突双方 Profile（010 文案不变）
5. **Given** 放 N 个 Agent 目录的目录，**When** 扫描注册，**Then** ProfileRegistry 出现 N 个、带 schedules 的都进了 AgentScheduler

---

### User Story 3 - 上下文注入：正文 + 绑定路径 + 渐进式披露守点（Priority: P1）

`ContextLoader.load(profile)` = **AGENT.md 正文**（现读、去 frontmatter、无缓存——坑三：缓存则"改完即时生效"破产）+ Bootstrap + 已绑定 Skill 元数据（注入**绑定路径** `<agentDir>/skills/<name>/SKILL.md`，修订说明 ⑦）；AGENT.md 缺失报错（坑四）；FILE_READ 动态白名单 = 静态 `file.allowed_paths` ∪ 当前 Agent 目录（坑七：不加则 read_file 读自己 REFERENCE.md/绑定 SKILL.md 全被拒，渐进式披露断链）；未绑定 Skill 实体路径（在 `.axion/skills/` 下）不可读——未绑定不可见。

**Why this priority**: 渐进式披露三守点的落地层——正文常驻（现读）、子指令/参考按需 read_file、未绑定不可见；正文即时生效是课件验收清单的硬项。

**Independent Test**: `ProgressiveDisclosureTest`（core 单测 + 临时目录 + 程序建软连接/junction，ContextLoaderTest 既有模式）：PromptBuilder 产物含正文不含 frontmatter；REFERENCE.md 内容与脚本代码不进 prompt；绑定路径 read_file 通过动态白名单、未绑定实体路径被拒；Skill 元数据注入绑定路径而非实体真实路径。

**Acceptance Scenarios**:

1. **Given** 有正文的 Agent，**When** PromptBuilder 组装 prompt，**Then** system 含正文、不含 frontmatter 段（坑一联动）
2. **Given** 改 AGENT.md 正文（不动 frontmatter），**When** 下一次 load，**Then** 用新正文（坑三：不重启、无缓存、即时生效）
3. **Given** AGENT.md 缺失，**When** load，**Then** 报错点名（坑四：不静默）
4. **Given** 已绑定 Skill（软连接/junction），**When** load，**Then** 元数据含 name + description + **绑定路径**（`<agentDir>/skills/<name>/SKILL.md`，非实体真实路径，修订说明 ⑦）
5. **Given** 当前 Agent 上下文，**When** read_file 自己的 REFERENCE.md / 绑定 SKILL.md，**Then** FILE_READ 通过（动态根 = 本 Agent 目录）；read_file 他 Agent 目录 / 未绑定实体路径，**Then** 被拒（坑七）

---

### User Story 4 - L3 脚本沙箱：解释器双白名单 + 简单形态 + scripts/ 限定（Priority: P1）

新配置键 `shell.allowed_interpreters`（③ 拍板配置化）：解释器命令首 token 须**同时命中** `shell.allowed_commands` 与 `shell.allowed_interpreters`（声明了但未进 commands 的解释器构造期 WARN，诊断只含配置键名）。解释器命令额外校验：① 无 Agent 上下文（`ProfileContext.current() == null`）**一律拒绝**（fail-closed）；② **简单形态钉死（坑九）**——只接受"解释器 + 单个 `scripts/` 下相对路径参数"，选项/引号/shell 元字符/链式一律拒绝；③ 脚本路径必须落当前 Agent `scripts/` 目录（越界抛 `SandboxViolationException`）。`ShellTools` 仅对解释器命令设子进程 cwd = Agent 目录（坑六）；非解释器命令行为不变。

**Why this priority**: "Agent 脚本按需跑"的安全边界——放行脚本 = 信任 Agent 作者（信任边界诚实标注）；简单形态同时封死首 token 校验挡不住的链式穿透。

**Independent Test**: `ProgressiveDisclosureTest`（解释器部分）+ `WhitelistSandboxTest`（007 类扩展：双白名单语义 + 子集 WARN）：简单形态放行；链式/选项/引号拒绝（坑九）；他 Agent scripts/ 与任意路径拒绝；无上下文拒绝；双白名单命中语义。

**Acceptance Scenarios**:

1. **Given** allowed_commands 含 python、allowed_interpreters 含 python，**When** enforce "python scripts/reconcile.py"（当前 Agent 上下文内），**Then** 放行
2. **Given** 链式 "python scripts/x.py ; curl http://evil" 或带选项 "bash -c '...'"，**When** enforce，**Then** 拒绝（坑九）
3. **Given** 脚本路径出本 Agent scripts/（他 Agent scripts/、任意绝对路径），**When** enforce，**Then** 拒绝（SandboxViolationException）
4. **Given** 无 Agent 上下文，**When** enforce 解释器命令，**Then** 拒绝（⑥ fail-closed）
5. **Given** allowed_interpreters 声明了未进 allowed_commands 的项，**When** 构造沙箱，**Then** WARN（永不命中，消息只含配置键名）
6. **Given** ShellTools 执行解释器命令，**When** 启动子进程，**Then** cwd = Agent 目录（坑六：相对路径按"这个 Agent 的目录"解析）；非解释器命令 cwd 行为不变

---

### User Story 5 - daily-reconcile 示例 Agent（Priority: P2）

按拍板②③改写课件 §1.3~1.4 全文：`daily-reconcile/`（AGENT.md：frontmatter 无 notify_channels、无 skills 字段，tools=[shell, read_file, notify, save_memory]，schedules 含 id；正文四步：跑脚本 → 判断 → 读规范写报告 → notify + save_memory，渠道按名引用）+ `scripts/reconcile.py`（纯标准库无 key）+ 公共技能实体 `.axion/skills/report-format/`（SKILL.md = 报告规范 + P0/P1/P2 分级）+ `REFERENCE.md`（字段字典/已知可接受差异）。四文件全文收录于设计文档 + 测试资源落位（AgentScanRegisterTest/ProgressiveDisclosureTest fixture）。

**Why this priority**: "在底座上定义一个会自己跑的 Agent"的参照物——手动路径与 30/31 节的模板；spec-kit 按课件产出，不手工搓。

**Independent Test**: 测试资源 fixture 被 US1/US3 测试消费（拆解/派生/披露）；人工项：放入工作区 → profile list 出现 → 到点自跑（真模型链路，验收标准·人工部分）。

**Acceptance Scenarios**:

1. **Given** 测试资源里 daily-reconcile 四文件 + 技能实体 + 绑定，**When** AgentLoader 解析，**Then** 派生 Profile 正确（tools/schedules/provider 全对）
2. **Given** 工作区放入 daily-reconcile 目录，**When** serve 启动，**Then** `axion profile list` 与 GET /api/v1/profiles 出现该 Agent——全程零 Java
3. **Given** 到点（或 runNow 补跑），**When** 触发，**Then** 脚本 JSON → 判断 → 报告 → 推送 → 留痕全链路完成（真模型人工项）

---

### User Story 6 - 装配接线与 Demo 前置（Priority: P2）

`CliAgentConfiguration` 适配（`AgentLoader.loadAll` 更名、`WhitelistSandbox`/`ShellTools` 构造参数接线 workspaceRoot + 解释器集合）；`application.yaml`：`shell.allowed_commands` 增补 `python`/`bash`（⑨：010 FR-7"保持原状"到期）+ 新键 `shell.allowed_interpreters: [python, bash]`（③ 拍板）；存量 Agent（weather/default）identity.prompt 与正文去重（⑩ 迁移注记）。

**Why this priority**: 装配是把六个交付物接成可运行整体的收口；存量迁移防"重复人格、token 膨胀"回退。

**Independent Test**: 全量 `mvn clean verify` 全绿（含 007/008/010 既有测试回归——前序 feature 契约证据）；人工项：存量 weather Agent 对话核对（无重复人格、行为不回退）。

**Acceptance Scenarios**:

1. **Given** 装配改造完成，**When** serve 启动，**Then** 扫描注册用 AgentLoader、沙箱/ShellTools 新参数接线成功、启动无异常
2. **Given** application.yaml 新配置，**When** 启动，**Then** allowed_interpreters 生效（解释器命令按 L3 校验处理）
3. **Given** 存量 weather Agent 去重后，**When** chat 对话，**Then** system prompt 无重复人格、仍会调 http_get（行为不回退）
4. **Given** 全模块测试，**When** mvn clean verify，**Then** 10 模块全绿（前序 feature 零回退）

---

### Edge Cases

- AGENT.md 缺 frontmatter / 未闭合 → 报错点名（003 已有）
- frontmatter name ≠ 目录名 → deriveProfile 报错（坑八：不静默、不晚失败）
- AGENT.md 在 load 时缺失 → 报错（坑四）
- 未绑定 Skill 实体路径、他 Agent REFERENCE.md → read_file 被拒（坑七）
- 解释器命令：链式/选项（脚本路径前）/引号/元字符 → 拒（坑九）；脚本参数透传（token[2..]，Clarifications 2026-09-13）；无 Agent 上下文 → 拒（⑥）；脚本出本 Agent scripts/ → 拒
- 非解释器命令（ls/cat 等）→ 首 token 白名单语义与 cwd 行为均不变（007 回归）
- registerProfile 同 id 冲突 → 报错文案与 010 一致
- 运行时注册与启动扫描同一校验（坑五：同一异常同一消息）
- allowed_interpreters 含未进 commands 的项 → 构造期 WARN、不产生放行面
- 坏目录扫描跳过不阻断启动（003 口径）

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: `AgentLoader`（`ProfileLoader` 更名 + 扩职责，003 交付物演进，落 axion-core）：读 AGENT.md 拆 frontmatter/正文（正文原样、同一解析器——坑一）；`detectResources(agentDir)` 认出 scripts//skills//REFERENCE.md；`deriveProfile(agentDir, providerNames)`（缺 name/provider 报错点名——坑二；**name 与目录名不一致报错——坑八**；schedules id 校验 010 ⑦c 延续）；`loadAll(agentsRoot, providerNames)`（坏目录记错误日志跳过）。`ProfileLoader` 删除，装配处与测试同步更名
- **FR-002**: `ContextLoader.load(profile)` = AGENT.md 正文（现读、去 frontmatter、**无缓存**——坑三）+ Bootstrap + 已绑定 Skill 元数据（注入**绑定路径** `<agentDir>/skills/<name>/SKILL.md`，修订说明 ⑦；绑定目标仍校验位于 `.axion/skills/` 内、逃逸即报错）；AGENT.md 缺失报错（坑四）
- **FR-003**: `ProfileRegistry` 增 `remove(String)`/`exists(String)`（register 已有）；运行时注册与启动扫描走同一段 deriveProfile + register（坑五：同一异常同一消息）
- **FR-004**: `AgentScheduler.registerProfile(Profile)`：registerAll 循环体抽出（登记 scheduled_tasks 含 next_run_at → 注册 cron 触发 → 句柄入 scheduledTasks → 注册信息入 registrations）；id 冲突查 registrations、报错文案与 010 一致；registerAll = 遍历 ProfileRegistry.list() 调 registerProfile
- **FR-005**: L3 脚本沙箱（007 演进，③ 拍板）：新配置键 `shell.allowed_interpreters`（`ShellSandboxProperties` 增字段）；解释器命令首 token 须**双白名单命中**（allowed_commands + allowed_interpreters；子集外声明构造期 WARN）；无 Agent 上下文拒绝（fail-closed）；**简单形态钉死（坑九）**——接受"解释器 + 单个 scripts/ 下相对路径参数（+ 可选脚本参数）"形态：token[1] 必须是 scripts/ 下相对路径、token[2..] 作为脚本参数透传（Clarifications 2026-09-13）；解释器与脚本路径之间的选项/引号/元字符/链式一律拒绝；脚本路径落本 Agent scripts/（Windows 大小写不敏感，007 ⑦b）；`ShellTools` 构造器增解释器集合 + workspaceRoot（agentDir = workspaceRoot/agents/<name> 派生）、仅解释器命令 cwd = Agent 目录（坑六）；`WhitelistSandbox` 构造器增 workspaceRoot；`Sandbox.enforce` 接口不变（宪法 VI）
- **FR-006**: FILE_READ 动态白名单 = 静态 `file.allowed_paths` ∪ 当前 Agent 目录（坑七：渐进式披露断链防线）；未绑定实体路径不可读（未绑定不可见）；FILE_WRITE 不动（最小权限）
- **FR-007**: daily-reconcile 示例 Agent（四文件 + 公共技能实体 + 绑定）：按拍板②③改写（frontmatter 无 notify_channels、无 skills 字段、正文渠道按名引用）；全文收录设计文档 + 测试资源落位（axion-core/src/test/resources）
- **FR-008**: 装配接线（003 装配处演进）：CliAgentConfiguration 改用 AgentLoader + 沙箱/ShellTools 新参数接线；application.yaml：allowed_commands 增补 python/bash（⑨）、新键 allowed_interpreters（③）；存量 Agent 身份/正文去重（⑩ 迁移注记）
- **NFR-001**: 全程同步阻塞（宪法 VII）；底座零重写——AgentService/ReActLoop/PromptBuilder/AgentScheduler 只吃 Profile，本节只改 Profile 来源（课件 §二原则）
- **NFR-002**: 正文即时生效（无缓存）+ 渐进式披露三守点（正文常驻 / 子指令按需 read_file / 脚本产出进上下文代码不进）（宪法 IV）
- **NFR-003**: 信任边界诚实标注：带脚本的 Agent = 信任 Agent 作者；沙箱对脚本只承诺"解释器（简单形态）+ 目录"两道白名单（劝阻级，007 口径）；容器/网络隔离归扩展阶段

### Key Entities

- **AgentLoader**（axion-core）：目录解析/派生——split/loadBody/detectResources/deriveProfile/loadAll
- **ContextLoader**（axion-core，002 演进）：正文注入 + 绑定路径元数据
- **ProfileRegistry**（axion-core，001 演进）：register/remove/exists/findByName/list
- **AgentScheduler**（axion-core，008/010 演进）：registerProfile + scheduledTasks 句柄表 + registrations
- **WhitelistSandbox / ShellTools / ShellSandboxProperties**（axion-tool，007/005 演进）：双白名单解释器校验 + FILE_READ 动态根 + cwd 绑定 + 新配置键
- **daily-reconcile 目录**（四文件）+ **公共技能实体 report-format**（SKILL.md）——无新表、无新模块

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 六个 harness 测试类全绿（AgentLoaderTest/DeriveProfileTest/AgentScanRegisterTest/ProfileRegistryRuntimeTest/AgentSchedulerRegisterTest/ProgressiveDisclosureTest + WhitelistSandboxTest 扩展）——坑一~坑九逐一对号（需求文档验收标准测试表）
- **SC-002**: 全量 `mvn clean verify` 10 模块全绿（含前序 001~010 测试零回退——前序 feature 全部测试回归绿）
- **SC-003**: 人工项：daily-reconcile 放入工作区 → profile list / GET /api/v1/profiles 出现 → 到点（或 runNow）自跑 → webhook 收到、llm_calls/tool_invocations 逐笔核对（真模型链路）
- **SC-004**: 人工项：改正文即时生效（不重启）；越界反例（他 Agent REFERENCE/脚本、链式命令、未绑定实体）全被拒且审计 success=false
- **SC-005**: 人工项：存量 weather Agent 对话核对——无重复人格、行为不回退（⑩ 迁移注记）

## Assumptions

- **前序交付物已实测就位**（2026-09-13）：Profile/ProfileRegistry（001）、ProfileLoader（003，本节更名演进）、ContextLoader 软连接/junction 解析 + ContextLoaderTest 绑定 fixture 模式（002）、AgentScheduler scheduledTasks/registrations（008/010）、WhitelistSandbox 三层白名单 + ShellTools（005/007）、ProfileContext ThreadLocal（002）、notify_channels 表 + NotifyTools（004）、llm_calls/tool_invocations 双审计（001/002/005）——纯增量 + 6 处改造点
- **拍板结论汇总**：Skill 按宪法 IV 软连接（2026-09-13）；notify_channels 按 004 拍板全局注册表；解释器集合配置化（③：新配置键 shell.allowed_interpreters）；L3 简单形态钉死（坑九）；name=目录名校验（坑八）；分支自 main 建、设计文档随工作树带入（010 先例）
- **改造点**（经拍板，需求文档交付物列）：ProfileLoader→AgentLoader（003）、ContextLoader（002）、ProfileRegistry（001）、AgentScheduler（008/010）、WhitelistSandbox/ShellTools/ShellSandboxProperties（005/007）、application.yaml（010 FR-7 到期解锁）、存量 Agent 去重（工作区运行时文件，⑩）
- **实现级口径**（需求文档修订说明 ⑤⑥⑦）：正文不进 Profile 值对象（路径级绑定）；解释器无上下文 fail-closed；Skill 注入绑定路径（技术方案 §12.2 验收口径）
- **明确不做**：/api/v1/agents API + 一句话生成 + WorkspaceWatcher（30 节）、Agent 版本/市场/同名策略、容器/网络隔离、加载失败清单可见性（30 节）、tools 告警运行时路径（30 节补）——三笔 30 节债已注记
