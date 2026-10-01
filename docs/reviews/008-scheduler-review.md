# 008-scheduler 代码 Review 指南

> 生成：2026-09-08（交付后复盘 + 人工验收闭环后）。
> 复盘全记录见 `specs/008-scheduler/flow-status.md`（含修订说明 ⑧ 三处实施中口径修正、SpotBugs 3 项修复、ErrorProne 2 项修复、人工验收 ①③ 实拍闭环），本指南是 review 导航。

## 一、全景：cron 到点 → 钟推 → 统一引擎

```
AGENT.md frontmatter schedules（cron/zone/message 三键，002/003 已交付；Spring 6 六段含秒——⑧b）
  → CliAgentConfiguration 装配：ThreadPoolTaskScheduler @Bean（setPoolSize(4)，⑦a）+ AgentScheduler @Bean
    （方法体内显式 registerAll，替代 @PostConstruct，②）
  → registerAll：扫 ProfileRegistry.list()（④ 适配）逐条 taskScheduler.schedule(runOnce, CronTrigger)
    ——CronTrigger(cron) 单参（zone 空按系统时区）/ CronTrigger(cron, validatedZone)（⑦b：ZoneId.of 校验，
    非法启动报错不静默回退 GMT）；schedule 句柄存 scheduledTasks Map（⑦c：28 节启停/重调度零重构）
  → 到点（Spring 的钟，taskScheduler-N 线程）runOnce：
      派生锁 key profileName|cron|message（拍板 B）→ per-task ReentrantLock tryLock
      ——拿不到 → log.info 跳过（坑二：不排队不并行）；拿到 → 三元组 getOrCreate("scheduler","scheduler",name)
      （宪法 VIII）→ agentService.process（与 CLI/Web 完全同一入口，审计零新增）
      ——catch Exception → 两行日志（sanitize taskKey 行 + 常量 Throwable 行，坑三不崩调度器）→ finally unlock
```

四坑 + 两个最值钱回归由 `AgentSchedulerTest` 10 用例钉死；装配由 `CliAgentConfigurationTest` 增补（bean 存在 + poolSize>1 回归钉）。

## 二、逐文件梳理

### axion-core/com/axion/core（1 新增 + pom 依赖，主代码零改动其余）

| 文件 | 关键点 |
|------|--------|
| `AgentScheduler` | 无组件注解纯类（G4-C1）：registerAll（④ list() 适配 + ⑦b zone 校验 + ⑦c 句柄 null 防护——mock schedule 返回 null 时跳过登记）；runOnce（锁生命周期：tryLock 跳过分支 + finally unlock；会话三元组；两行日志形态——(String, Object) sanitize 行 + 常量 Throwable 行，FindSecBugs CRLF 门禁形态）；sanitize 净化 CR/LF（005 先例）；EI_EXPOSE_REP2 抑制（004 先例，注入单例只读）；package-private 测试钩子 lockFor（课件骨架同款）与 scheduledTaskCount（⑦c 断言） |
| `pom.xml` | +spotbugs-annotations（provided）——004/006/007 各模块用抑制注解时各自声明的机械延伸 |
| 前序公共接口（Profile/Profile.Schedule/ProfileRegistry/SessionManager/AgentService） | **零改动**（git diff 空——拍板 B 承诺兑现） |

### axion-cli（1 改造）

| 文件 | 关键点 |
|------|--------|
| `CliAgentConfiguration` | +ThreadPoolTaskScheduler @Bean（setPoolSize(4) ⑦a + initialize，容器关闭 DisposableBean 自动 shutdown；Boot 自动装配同名 bean 被顶替）+ AgentScheduler @Bean（构造注入四依赖 + registerAll 显式调用 ②） |

### 测试（2 文件）

| 文件 | 关键点 |
|------|--------|
| `AgentSchedulerTest` | 10 用例：注册参数 equals 断言（⑧ H3 核实 CronTrigger 无公开 getTimeZone）；锁占跳过用**虚拟线程**模拟调度线程持有（⑧c：ReentrantLock 可重入，课件骨架同线程 lock() 后 tryLock() 恒成功走不到跳过分支）；二进宫锁释放（最值钱）；三元组 verify；跳过/失败日志 ListAppender 断言（两行形态）；⑦b 非法 zone 启动报错；多 Profile 全量注册 + ⑦c 句柄计数；zone 空单参 |
| `CliAgentConfigurationTest` | +AgentScheduler bean 存在 + `getScheduledThreadPoolExecutor().getCorePoolSize() > 1`（⑦a 回归钉） |

## 三、重点 review 清单（按风险排序）

1. **runOnce 锁生命周期**（`AgentScheduler.java:85-101`）：tryLock 失败 → return 之前**没有** lock 需要释放（跳过分支不持有锁）；成功路径 finally unlock——「二进宫」测试（异常后再跑一次 verify times(2)）是唯一能抓住 finally 漏 unlock 的断言
2. **⑦b zone 校验**（`validatedZone`）：`ZoneId.of` 先校验再 `TimeZone.getTimeZone`——顺序反了（先 getTimeZone 后校验）则非法值已静默回退 GMT、校验永远通过；回归钉 = 非法 zone 启动报错测试
3. **⑦a 调度池**（`CliAgentConfiguration.java:135-141`）：`setPoolSize(4)` 必须在 `initialize()` 之前；删掉这一行 = 默认单线程、长 ReAct 跨任务互相阻塞（防重叠锁只管同任务——四文档之外盲点）
4. **⑦c 句柄 null 防护**（`registerAll` 内 `if (future != null)`）：ConcurrentHashMap 不接受 null 值——mock 调度器返回 null 时 put 会 NPE；防护不削弱契约（真实调度器恒非空）
5. **闭包捕获启动时 Profile**（`registerAll` 的 lambda）：注册后改 AGENT.md 不影响已注册闭包——"改 cron 需重启"的口径闭环（核心阶段边界）
6. **两行日志形态**（⑧a 澄清）：sanitize taskKey 行 + 常量 Throwable 行——FindSecBugs CRLF 门禁只认形态；用户可控值不进带 Throwable 的重载（001 纪律）
7. **cron 六段**（⑧b）：Spring 6 `CronExpression.parse` 只接受 6 段——CLAUDE.md 示例已修正为 `0 0 8 * * *` 并注记
8. **测试钩子**（lockFor/scheduledTaskCount，package-private）：课件骨架 lockFor 同款 + ⑦c 断言需要；非对外 API、不进交付清单

## 四、刻意留白（review 时不要当成缺陷报）

1. **任务状态持久化与管理（28 节）**：scheduled_tasks/task_executions 两张表、ScheduledTaskStore、ScheduleApiController 四端点——技术方案 §8.5 明文「第 28 节补齐」；⑦c 句柄 Map 正是为它零重构预留
2. **task_id 来源**：28 节必须重议（⑦d）——锁 key 含 message（自然语言、改一字重启即漂移），候选：给 Schedule 补 id / 换 profileName+cron+zone 稳定派生；25 节拍板 B 不改
3. **分布式协调**：单实例本地锁只防同进程重叠；多实例归属（选主/租约）扩展阶段与「状态外置」一起做
4. **失败重试/告警、misfire 补跑、优雅停机、日志节流**：核心阶段「失败不崩、留痕可查」就够（课件「有几样先别做」）
5. **运行时增删改 cron**：依赖扩展阶段的 Agent 目录上传 + 调度运行时接口；核心阶段改 cron 重启生效
6. **scheduledTaskCount 句柄未暴露 cancel 能力**：25 节只存不用——28 节启用停用时再消费，克制原则

## 五、建议 review 顺序

1. `AgentScheduler`（registerAll → validatedZone → runOnce 锁生命周期——先看懂钟推主链）
2. `AgentSchedulerTest`（四坑 + 两最值钱 + 虚拟线程锁模拟 ⑧c + 日志断言）
3. `CliAgentConfiguration` 两个新 @Bean（⑦a poolSize + registerAll 时机）+ `CliAgentConfigurationTest` 增补
4. `docs/requirements/008-scheduler.md` 修订说明 ⑧（三处实施中口径修正的来龙去脉）+ CLAUDE.md cron 示例修正

## 六、当前验收状态

- **人工验收闭环**（2026-09-08）：
  1. ① 真实到点触发 ✅——`SchedulerManualIT`（临时 harness，已删）路径 B 完整版实拍：`[taskScheduler-1]` 线程真实触发（cron `*/30 * * * * *`）→ 3 轮 LLM（第 1/2 轮含工具请求、第 3 轮最终答复）→ `llm_calls` 3 行（session=`scheduler|scheduler|weather-agent`、success=true、tokens/durationMs 有值）+ `tool_invocations` 2 行（http_get success=true——wttr.in 过 007 白名单）——四段证据全绿（用户 PowerShell 实跑，54.9s）
  2. ③ 端到端预演 ✅ 随 ① 一并闭环（= 31 节 Demo 一钟推地基实拍）
  3. ② 配置驱动体感 ⚠️ 机器证据覆盖（坑一测试 + ① 实拍 AGENT.md 驱动注册），手工体感留用户可选
  4. ④ CLAUDE.md 五段 cron 示例 ✅ 按方案 A 修正（`0 0 8 * * *` + 注记）
- `mvn clean verify` 全绿：210 tests + 全静态门禁（Spotless/SpotBugs/ErrorProne）；SpotBugs 3 项修复（CRLF 两行日志形态 + EI_EXPOSE_REP2 按 004 先例抑制）；ErrorProne LockNotBeforeTry 两处修正
- **实施中三处口径修正（修订说明 ⑧）**：a. NFR-3 澄清（message 属运营配置非运行时用户可控值）；b. Spring 6 cron 六段（H3 实测，CLAUDE.md 已修）；c. 课件锁测试骨架同线程可重入失效 → 虚拟线程模拟
- **剩余待办**：无实质遗留——② 手工体感可选；28 节 task_id 重议为显式前置任务（⑦d）
