# Data Model: 011-plugin-agent

> Phase 1 输出。**无新表**——本节的"数据"是 Agent 目录结构、校验规则与配置键 schema；Profile 值对象零字段变更（research R2）。

## 一、Agent 目录结构（唯一真相源）

```text
.axion/agents/<name>/            # 一个目录 = 一个 Agent（目录名 = frontmatter name，坑八校验）
├── AGENT.md                      # 必填：frontmatter（运行配置）+ 正文（任务指令）
├── REFERENCE.md                  # 可选：参考（字段字典/已知差异）
├── scripts/                      # 可选：脚本（解释器命令经 L3 校验后运行）
└── skills/<skill-name>           # 可选：指向 .axion/skills/<skill-name>/ 的相对软连接/junction（绑定真相源）
.axion/skills/<skill-name>/      # 公共技能实体：SKILL.md（frontmatter: name/description + 正文）
```

**身份规则**：`name` = 目录名（坑八：不一致 deriveProfile 报错点名）；**绑定规则**：软连接集合是唯一绑定真相源，frontmatter MUST NOT 声明 skills（宪法 IV）；**渠道规则**：frontmatter 无 notify_channels，正文按名引用（004 拍板）。

## 二、AgentLoader 解析规则

| 规则 | 行为 | 坑对号 |
|------|------|--------|
| AGENT.md 缺失 | IllegalArgumentException 点名路径 | 003 已有 |
| frontmatter 缺失/未闭合 | 报错点名 | 003 已有 |
| frontmatter 非 YAML 映射 | 报错点名 | 003 已有 |
| 正文拆分 | frontmatter 之后原样文本，不做二次加工 | 坑一 |
| 缺 name / provider | 报错点名文件与缺失键 | 坑二 |
| name ≠ 目录名 | 报错点名（派生期失败，不晚失败） | 坑八 |
| provider 引用全局层不存在 | 报错点名（001 口径） | 001 已有 |
| schedules 条目缺 id | 报错（010 ⑦c：不静默、不派生兜底） | 010 已有 |
| 坏目录 | 记错误日志跳过、不阻断启动 | 003 口径 |

## 三、ContextLoader 注入结构（每轮现读，无缓存）

```
load(profile) =
  ① AGENT.md 正文（agents/<name>/AGENT.md 现读去 frontmatter；缺失报错——坑四）
  ② Bootstrap 文件（003 既有；缺失报错）
  ③ Skill 元数据（每绑定一条）：
     "- 技能: {name} — {description}（读取路径: <agentDir>/skills/<name>/SKILL.md）"
     绑定路径 = agentDir + 绑定名（修订说明 ⑦；目标仍校验位于 .axion/skills/ 内、逃逸报错）
```

## 四、L3 脚本沙箱判定状态机（SHELL_COMMAND 分支）

| 步骤 | 条件 | 结果 |
|------|------|------|
| 1 | 首 token ∉ `shell.allowed_commands` | 拒绝（007 既有："命令不在白名单内"） |
| 2 | 首 token ∉ `shell.allowed_interpreters` | 放行（非解释器命令，007 语义原样） |
| 3 | 首 token ∈ 双白名单 且 ProfileContext 无当前 Agent | **拒绝**（fail-closed，⑥） |
| 4 | 命令含引号 / shell 元字符（`;` `\|` `&` `$(` `` ` `` `>` `<` 换行）/ 脚本路径前的选项 token | **拒绝**（坑九：链式/引号/解释器级选项） |
| 5 | token[1] 不是 `scripts/` 下相对路径（或缺失） | **拒绝**（裸解释器/越界路径） |
| 6 | token[1] 绝对路径化后 ∉ 当前 Agent `scripts/` 目录（Windows 大小写不敏感，007 ⑦b） | 拒绝（SandboxViolationException） |
| 7 | 全部通过 | 放行；token[2..] 作为脚本参数透传（S2 澄清）；ShellTools 子进程 cwd = Agent 目录（坑六） |

**配置键 schema**（`ShellSandboxProperties` 增字段）：

```yaml
shell:
  allowed_commands: [ls, cat, python, bash]     # 既有键：可执行命令白名单（010 FR-7 到期增补）
  allowed_interpreters: [python, bash]          # 新键（③ 拍板）：解释器子集；未进 commands 的项构造期 WARN（永不命中）
```

## 五、FILE_READ 判定（checkFilePath 扩展）

| 分支 | 白名单根 | 说明 |
|------|---------|------|
| FILE_READ | 静态 `file.allowed_paths` ∪ 当前 Agent 目录（`workspaceRoot/agents/<name>`；无上下文 = 仅静态） | 坑七：自己 REFERENCE.md/绑定 SKILL.md 可读；他 Agent 与未绑定实体不可读 |
| FILE_WRITE | 静态 `file.allowed_paths`（原样） | 最小权限：Agent 不需要写自己的目录 |

## 六、运行时注册数据流（30 节契约前置）

```
启动扫描：AgentLoader.loadAll(agentsRoot) → Map<name,Profile> → ProfileRegistry.register × N → AgentScheduler.registerAll
运行时注册（30 节同段代码）：AgentLoader.deriveProfile(agentDir) → ProfileRegistry.register → AgentScheduler.registerProfile
ProfileRegistry：register（覆盖语义，001）/ remove（幂等）/ exists / findByName / list
AgentScheduler.registerProfile：id 冲突查 registrations（报错文案同 010）→ store.register → taskScheduler.schedule → scheduledTasks + registrations
```
