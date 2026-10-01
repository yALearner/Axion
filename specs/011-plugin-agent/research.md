# Research: 011-plugin-agent

> Phase 0 输出。全部 NEEDS CLARIFICATION 已由设计文档修订说明 ①~⑩ + S2 澄清（脚本参数透传）解决；本文件记录实现级技术决策与取舍理由。

## R1 · AgentLoader 更名与拆分职责（FR-001）

- **Decision**: `ProfileLoader` → `AgentLoader`（core 同包）；`split(content)` 拆 frontmatter/正文、`loadBody(agentDir)` 现读正文、`detectResources(agentDir)` 认资源、`deriveProfile`/`loadAll` 沿用 003 逻辑 + 坑八校验
- **Rationale**: 课件交付物点名 AgentLoader（CLAUDE.md 原则四/技术方案 §11.2/DemandAnalysis §5.2 同口径）；拆分与派生同一解析器是坑一（两套解析各拆各的会 frontmatter/正文错位）
- **Alternatives considered**: 保留 ProfileLoader 名、另建 AgentLoader 包装——否决：同职责两个类徒增困惑，003 交付物允许本节演进（需求文档 ④ 拍板）

## R2 · 正文不进 Profile 值对象（修订说明 ⑤）

- **Decision**: Profile 零字段变更；正文由 ContextLoader 每轮 `agents/<name>/AGENT.md` 现读、去 frontmatter；资源路径一律 `workspaceRoot + name` 派生
- **Rationale**: 坑三铁律（无缓存、改完即时生效，002 坑五同款）；路径级绑定已够（name 唯一 = 目录名，坑八校验保证不串）
- **Alternatives considered**: Profile 加 `body` 字段——否决：缓存即"改完即时生效"破产；Profile 加 `agentDir` 字段——否决：可由 name 派生，冗余字段引入不一致风险

## R3 · Skill 元数据改注入绑定路径（修订说明 ⑦）

- **Decision**: ContextLoader 注入 `<agentDir>/skills/<name>/SKILL.md`（绑定路径，经软连接/junction 透传读实体）；绑定目标校验（位于 `.oryxos/skills/` 内、逃逸报错）保留 002 逻辑
- **Rationale**: 技术方案 §12.2 验收原文"`tool_invocations` 有对 Agent 本地软连接路径的 `read_file`"即绑定路径口径；配合 FILE_READ 动态根（R5）实现"未绑定不可见"——未绑定实体路径在 `.oryxos/skills/` 下、不在 Agent 目录下，天然不可读
- **Alternatives considered**: 继续注入真实路径 + 沙箱动态并入"已绑定技能真实目录集合"——否决：沙箱须耦合绑定解析逻辑，且实体路径泄露进 prompt

## R4 · 解释器配置化（③ 拍板）+ 双白名单语义

- **Decision**: 新配置键 `shell.allowed_interpreters`（`ShellSandboxProperties` 增 `List<String> allowedInterpreters`）；解释器命令 = 首 token **同时命中** `allowed_commands` 与 `allowed_interpreters`；声明了但未进 commands 的项构造期 WARN（消息只含配置键名，007 ⑦c 诊断纪律）
- **Rationale**: 扩展性——未来 node/jq 脚本只改配置不改代码（用户拍板配置化）；双白名单让"哪些命令能跑"与"哪些命令是解释器"职责分开
- **Alternatives considered**: 硬编码 `{python,python3,bash,sh}`——否决（四维分析 ③，用户拍板）；独立生效的 interpreters 白名单（不要求 ⊂ commands）——否决：两处声明漂移，子集校验 + WARN 把配置错误暴露在启动期

## R5 · FILE_READ 动态根（坑七）

- **Decision**: `checkFilePath` 的 FILE_READ 分支 = 静态 `file.allowed_paths` ∪ 当前 Agent 目录（`workspaceRoot/agents/<name>`，经 ProfileContext；无上下文按静态白名单）；FILE_WRITE 不动
- **Rationale**: 渐进式披露断链防线——不加则 read_file 读自己 REFERENCE.md/绑定 SKILL.md 全被拒；最小权限——他 Agent 目录、未绑定实体路径、写入自己的目录都不需要放开
- **Alternatives considered**: 动态根扩到 `.oryxos/skills/` 整体——否决：任何 Agent 可猜路径读未绑定技能，违反"未绑定不可见"

## R6 · L3 简单形态判定规则（坑九 + S2 澄清）

- **Decision**: 解释器命令仅接受 `解释器 + token[1]=scripts/ 下相对路径 + token[2..]=脚本参数透传`；整条命令含引号（`'`/`"`）、shell 元字符（`;` `|` `&` `$(` `` ` `` `>` `<` 换行）或解释器级选项（脚本路径前出现 `-` 开头 token）→ 拒绝；无路径 token（裸解释器）→ 拒绝；无 Agent 上下文 → 拒绝
- **Rationale**: 坑九封死首 token 校验挡不住的链式穿透（`python scripts/x.py ; curl ...`）；S2 澄清：脚本参数透传（安全边界是脚本路径本身，脚本 = 信任的代码，参数不进攻击面）；课件与技术方案 §12.3 示例均为"解释器 + 单路径"简单形态，钉死零牺牲
- **Alternatives considered**: 精确解析 shell 语法区分链式与合法参数——否决：解析 shell 语法是引入完整攻击面；恰好两 token——否决（S2 用户拍板允许脚本参数）

## R7 · 解释器命令 cwd 绑定（坑六）

- **Decision**: `ShellTools` 构造器增解释器集合（装配处从 ShellSandboxProperties 注入）；execute 时首 token ∈ 解释器集合 → `ProcessBuilder.directory(agentDir)`（agentDir 由 ProfileContext + 注入的 workspaceRoot 派生）；非解释器命令行为不变（cwd 默认 = JVM 启动目录）
- **Rationale**: 坑六——cwd 不设则 `python scripts/foo.py` 按进程启动目录解析，要么找不到、要么跑到别的目录的同名脚本；只对解释器命令生效，存量 ls/cat 行为零回归
- **Alternatives considered**: 所有命令 cwd=Agent 目录——否决：存量 Agent 的 ls/cat 相对路径语义被改，007 回归风险

## R8 · 无 Agent 上下文 fail-closed（修订说明 ⑥）

- **Decision**: `ProfileContext.current() == null` 时解释器命令一律拒绝（SandboxViolationException）；FILE_READ 动态根缺位时按静态白名单（既有语义）
- **Rationale**: 工具执行只发生在 AgentService.process 内（ProfileContext 必被设置，002 坑四钉死）——上下文缺失意味着绕开处理流程的调用，拒绝是唯一安全语义；FILE_READ 静态兜底是 007 既有行为，不动
- **Alternatives considered**: 无上下文按"无动态根、解释器全拒"之外的任何放行——否决：放行面不可控

## R9 · 测试 fixture 与 junction 策略

- **Decision**: 示例 Agent 四文件 + 技能实体提交 git（oryxos-core/src/test/resources）；skills 绑定由测试程序创建（Windows `cmd /c mklink /J` junction、POSIX `Files.createSymbolicLink`，ContextLoaderTest 既有工具方法复用）；测试用 @TempDir 拷贝 fixture 后建绑定
- **Rationale**: junction/软连接是文件系统对象，git 无法提交；测试期程序创建既保证可移植又钉死绑定解析路径
- **Alternatives considered**: fixture 里预建 junction 提交——否决：不可移植（Windows junction vs POSIX 软连接不可互换）；运行时工作区 `.oryxos` 提交——否决：gitignored，运行时目录不属于交付物

## R10 · 存量 Agent 迁移（修订说明 ⑩）

- **Decision**: 工作区运行时文件（`.oryxos/agents/weather|default`）由人工去重（identity.prompt 留人格、正文留任务步骤），不产生代码交付物；人工验收含一次 weather 对话核对
- **Rationale**: 工作区 gitignored、非交付物；迁移本质是内容编辑而非机制变更，人工项即可
- **Alternatives considered**: 代码层去重（如注入时跳过与 prompt 重复的正文）——否决：语义猜测不可靠，"重复人格"应由作者自己收敛
