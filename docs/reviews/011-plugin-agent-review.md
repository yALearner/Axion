# 011-plugin-agent 代码 review 指南

> 全景调用链 / 逐文件梳理 / 重点清单 / 刻意留白 / 建议顺序 / 验收状态（010-review 同款六段式）

## 一、全景调用链（本节交付后）

```
启动：CliAgentConfiguration.profileRegistry → AgentLoader.loadAll(.axion/agents) → deriveProfile × N
      → ProfileRegistry.register × N → AgentScheduler.registerAll → registerProfile × N
      → store.register + taskScheduler.schedule → scheduledTasks/registrations 句柄
触发：runOnce/runNow → agentService.process（宪法 VIII，与 CLI/Web 同一入口）
每轮：PromptBuilder.build → ContextLoader.load(profile) =
      [① AGENT.md 正文（AgentLoader.loadBody 现读去 frontmatter，坑三无缓存）
       ② Bootstrap ③ 已绑定 Skill 元数据（绑定路径 <agentDir>/skills/<name>/SKILL.md，修订说明 ⑦）]
      → ProviderService（llm_calls）→ ToolExecutor（tool_invocations）
      → read_file/shell → WhitelistSandbox.enforce：
         FILE_READ = 静态 file.allowed_paths ∪ 当前 Agent 目录（ProfileContext，坑七）
         SHELL_COMMAND 解释器（双白名单）→ 简单形态（坑九）→ 脚本限本 Agent scripts/
      → ShellTools 解释器命令 cwd = Agent 目录（坑六）
```

## 二、逐文件梳理

| 文件 | 改动性质 | 看什么 |
|------|---------|--------|
| `core/AgentLoader.java` | ProfileLoader 更名 + 扩职责（改造点 ④ 拍板） | split 与 deriveProfile 同解析器（坑一）；name=目录名校验位置（坑八，derive 期不晚失败）；FrontMatterAndBody 防御拷贝；报错文案与 003/010 逐字一致 |
| `core/ContextLoader.java` | 002 演进（FR-2） | loadBody 现读无缓存；绑定路径注入仍先过 resolveRealSkillDir 逃逸校验；AGENT.md 缺失报错（坑四） |
| `core/ProfileRegistry.java` | 001 演进（FR-3） | remove 幂等；register 覆盖语义不变 |
| `core/AgentScheduler.java` | 008/010 演进（FR-4） | registerProfile 循环体与 registerAll 逐字同逻辑；id 冲突报错文案与 010 一致；HashMap import 已删 |
| `tool/WhitelistSandbox.java` | 007 演进（FR-5/FR-6） | enforce 四值路由不变（宪法 VI 接口零改动）；解释器分支在首 token 白名单**之后**（非解释器 007 语义原样）；元字符全命令扫描；FILE_WRITE 未动；子集 WARN 只含配置键名 |
| `tool/ShellTools.java` | 005 演进（坑六） | bindAgentWorkingDir 仅解释器 + 有上下文时设 cwd；非解释器零回归 |
| `tool/ShellSandboxProperties.java` | ③ 拍板 | 双字段归一空列表；@ConfigurationProperties 前缀不变 |
| `cli/CliAgentConfiguration.java` | 装配 | WhitelistSandbox/ShellTools 新参数接线（Path.of(".axion")）；AgentLoader 引用 |
| `boot/application.yaml` | 配置（⑨/③） | allowed_commands + python/bash；allowed_interpreters 新键注释 |
| `web/api/AgentApiController.java`、`ProfileApiController.java`、`core/AgentService.java` | EI_EXPOSE_REP2 抑制 | 装配注入单例只读使用，AgentScheduler 同款先例；无行为变化 |

## 三、重点 review 清单（按风险排序）

1. **WhitelistSandbox.enforceInterpreterCommand**（tool，安全边界本体）：元字符集合是否覆盖 `$()`/反引号/引号/重定向/glob；`agentDir.resolve(scriptToken).normalize()` 是否吞掉 `../` 穿越（坑九回归 ProgressiveDisclosureTest 钉死）；`tokens[1].startsWith("-")` 与脚本参数透传的边界（`--date` 在 token[2..] 放行、在 token[1] 拒绝——S2 澄清口径）
2. **ContextLoader.loadBody 每轮现读**：无任何缓存字段（坑三）；`agents/<name>` 由 profile.name 派生与坑八校验的闭环（name≠目录名在派生期已拦）
3. **绑定路径注入 vs 逃逸校验**：skillMetadataOf 注入 binding 路径但仍调 resolveRealSkillDir 校验目标位于 `.axion/skills/`（002 逻辑保留）——两者缺一不可
4. **registerProfile 与 registerAll 一致性**：id 冲突改查 registrations 后，报错文案与 010 逐字一致（停止清单第 2 条）；registrations.put 仍在 schedule 之后（与 010 顺序一致，部分失败语义不变）
5. **FILE_READ 动态根**：无上下文静态兜底（不扩大放行面）；FILE_WRITE 确未触碰（最小权限）
6. **改造点波及**：PromptBuilderTest/ReActLoopTest/CliAgentConfigurationTest 补 agent 目录 fixture 是否最小侵入（未改断言语义）

## 四、刻意留白（本节不做，均为已注记）

- `unregisterProfile` 与句柄注销：30 节（scheduledTasks 句柄表已就位，contracts §3）
- 运行时注册入口（API/Watcher）：30 节（ProfileRegistry.register/remove/exists + registerProfile 已就位）
- 启动加载失败 Agent 的清单可见性：30 节（当前仅 ERROR 日志，观测盲区）
- 容器/网络隔离、Agent 版本/市场：扩展阶段
- tools 引用告警运行时路径：30 节补（005 装配校验仅覆盖启动）

## 五、建议阅读顺序

spec/plan 摘要 → `contracts/agent-runtime.md`（30 节契约 + 三笔债）→ `data-model.md` §四（L3 状态机）→ AgentLoader → ContextLoader → WhitelistSandbox（核心）→ ShellTools → 装配/配置 → 六个测试类（坑一~九对号）→ CLAUDE.md 陷阱表 +7 条

## 六、验收状态

- 机器判卷：`mvn clean verify -Dskip.npm` 全 10 模块绿（287 tests，Spotless/P3C/SpotBugs/FindSecBugs/PMD/ErrorProne 全过，2026-09-14）
- 剩余人工项（quickstart 五步）：① daily-reconcile 手工路径 + profile list 出现 ② 真模型到点自跑（真 key + webhook + 审计逐笔）③ 正文即时生效反例 ④ 越界反例（含链式命令）⑤ 存量 weather Agent 对话核对（⑩ 迁移注记）
- 遗留：010 遗留四条待办不属本节（成败语义文案/停用提示/§9.2 updated_at/真 key 人工项）
