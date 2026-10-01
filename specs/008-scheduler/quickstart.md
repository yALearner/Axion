# Quickstart 验证指南: 008-scheduler

> 验证分两层：机器判卷（harness 全绿）+ 人工项（真实到点触发）。本节自身无独立 Demo——验收以 harness 全绿 + 真实到点触发一次为准（需求文档「跑通标准」；31 节两个定时 Demo 消费本地基）。

## 前置

- Java 21 + Maven；前序交付物就位（实测 2026-09-07）：`Profile.Schedule` 三字段、`ProfileRegistry.list()`、`SessionManager.getOrCreate`、`AgentService.process(Session, String)`——纯增量无缺口

## 机器判卷：harness 全绿

```bash
mvn test -pl axion-core -am                    # 日常全跑（AgentSchedulerTest 四坑 + 两最值钱 + 三元组 + ⑦b）
mvn clean verify                                # 收尾全量门禁（全绿，不写死用例数——007 ⑦e 口径）
```

关键回归点对号（需求文档验收标准 harness 表 + ⑦ 项）：

| 测试点 | 关键回归 |
|--------|---------|
| 注册参数对 | ArgumentCaptor 抓 `schedule(Runnable, Trigger)`：CronTrigger 带配置 cron + 时区（坑四）；zone 空 → 单参（系统时区） |
| **⑦b 非法 zone** | `zone=Asia/Shangha`（拼错）→ registerAll 抛 IllegalStateException，message 含 profile 与 zone（001 纪律不静默） |
| 重叠跳过（坑二） | 先占锁再 runOnce → `verify(agentService, never()).process(...)` + 跳过日志 |
| **最值钱：异常不外抛 + 锁释放** | process 抛 RuntimeException → `assertDoesNotThrow(runOnce)` + **再跑一次** `verify(times(2))`（二进宫——finally 漏 unlock 只有它能抓住） |
| 会话三元组 | `verify(sessionManager).getOrCreate("scheduler", "scheduler", profileName)`；两次 runOnce 同一 Session |
| 注册全扫描 | 多 Profile 多 schedules → schedule 调用次数 = schedules 总数 |
| `CliAgentConfigurationTest` 增补 | AgentScheduler bean 存在；**`getScheduledThreadPoolExecutor().getCorePoolSize() > 1`**（⑦a 默认单线程跨任务阻塞回归钉）；空 registry 启动无异常 |

## 人工项（做完怎么验）

1. **真实到点触发一次**（课件 §五）：AGENT.md 的 schedules 配"每分钟"（cron `0 * * * * *`），到点看到 Agent 自动发起对话、`llm_calls`/`tool_invocations` 有账——cron 触发链路本身只能真等一次（harness 测的是注册参数与 runOnce 行为，不是 Spring 的钟）
2. **配置驱动体感**：改 AGENT.md 的 cron 表达式不用重新编译，重启后按新时间跑
3. **端到端预演**：完整走一遍"到点自动触发 → 跑完 ReAct 循环 → 留下审计记录"，为 31 节两个定时 Demo 踩实地基（注意：Demo 的 http_get 域名需在 007 白名单内——`wttr.in` 已就位）
