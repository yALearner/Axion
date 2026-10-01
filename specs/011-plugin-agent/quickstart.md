# Quickstart: 011-plugin-agent 验证指南

> Phase 1 输出。机器判卷部分（harness 六类 + 沙箱扩展）全绿即过；人工部分按 manual-acceptance.md 的「临时 harness」模式执行（真装配 + 真落库 + 真模型，验收后删除）。

## 前置

- Java 21 + Maven；实施环境 export 后构建（JAVA_HOME/PATH，002 先例）
- 人工项需：`DEEPSEEK_API_KEY`、`OPS_WEBHOOK_URL`（飞书/企微 webhook）、本机 `python` 在 PATH、Windows 下 junction 特权
- 已建分支 `011-plugin-agent`；`mvn clean verify` 前先 `mvn clean`（010 实录：IDE 污染 target/ 报 Unresolved compilation problem）

## 机器判卷（harness）

```bash
# 全量门禁（10 模块；含 Spotless/P3C/SpotBugs/FindSecBugs/PMD——DoD 1）
mvn clean verify

# 本 feature 单测（core 六类 + tool 沙箱扩展）
mvn -pl axion-core -am test -Dtest='AgentLoaderTest,DeriveProfileTest,AgentScanRegisterTest,ProfileRegistryRuntimeTest,AgentSchedulerRegisterTest,ProgressiveDisclosureTest'
mvn -pl axion-tool -am test -Dtest='WhitelistSandboxTest'

# 前序 feature 回归（DoD 4：跨 feature 契约证据）
mvn -pl axion-core,axion-tool,axion-cli -am test
```

预期：坑一~坑九逐一对号（正文原样/报错点名/name=目录名/现读即时生效/AGENT.md 缺失报错/同一异常同一消息/未绑定不可读/链式拒绝/cwd 绑定）；双白名单与子集 WARN 回归绿；前序 001~010 测试零回退。

## 人工项（做完怎么验）

### 1. 一个目录定义一个 Agent（零 Java）

```bash
axion init                                   # 幂等
# 把 daily-reconcile 四文件放入 .axion/agents/daily-reconcile/（设计文档全文收录）
# skills 绑定：Windows 用 mklink /J（POSIX 用软连接）指向 .axion/skills/report-format/
axion serve                                  # 启动（定时任务随 serve 常驻）
axion profile list                           # 预期：出现 daily-reconcile
curl http://localhost:8080/api/v1/profiles    # 预期：同上（009 端点）
```

### 2. 真模型链路到点自跑（真 key，008/010 SchedulerFlowIT 同款）

- 前置：`notify_channels` 表 INSERT team-ops（真 webhook）；`RECON_ORDERS_CSV`/`RECON_SETTLE_CSV` 两环境变量指向昨日两库导出 CSV
- 到点（或 POST /schedules/reconcile-morning/run 补跑）→ 预期：webhook 收到分级报告；`sessions` scheduler 会话复用；`tool_invocations` 逐笔核对——`shell` 跑脚本（成功）、`read_file` 读绑定路径（`<agentDir>/skills/report-format/SKILL.md`）、`notify`（result_json 带渠道名）、`save_memory`——全部 success；`llm_calls` 多条

### 3. 正文即时生效反例

改 AGENT.md 正文一处措辞（不动 frontmatter）→ 不重启、下一轮触发用新措辞；改 frontmatter temperature → 需重启才生效。

### 4. 越界反例（全部被拒 + tool_invocations success=false）

- read_file 他 Agent REFERENCE.md / 未绑定技能实体路径
- shell：`python scripts/reconcile.py ; curl http://evil`（链式）、`bash -c '...'`（选项）、他 Agent scripts/ 脚本、无 Agent 上下文的解释器命令

### 5. 存量 Agent 迁移核对（修订说明 ⑩）

`axion chat` 对话 weather Agent：system prompt 无重复人格（identity.prompt 与正文已去重）、仍会调 http_get（行为不回退）。

## 验收清单（课件 §三逐条）

| 项 | 判卷方式 |
|----|---------|
| 一个目录 = 一个 Agent（profile list / GET /profiles 出现） | 人工 1 |
| 定时来自 Agent（到点真触发、webhook 收到、审计有账） | 人工 2（真 key） |
| 资源按需加载（read_file 按需读、shell 跑脚本、代码不进上下文） | harness ProgressiveDisclosureTest + 人工 2 核对 |
| 正文即时生效 | 人工 3 |
| 两条来源同规矩（同一套校验） | harness ProfileRegistryRuntimeTest（坑五） |
| 运行时注册就位（立即可见 + 定时句柄） | harness AgentScanRegisterTest / AgentSchedulerRegisterTest |
| 不回退（mvn clean verify 全绿） | 机器判卷 |
