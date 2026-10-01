# Contract: AgentScheduler 钟推行为契约

> 本节对外概念 = `AgentScheduler`（技术方案 §8.5 点名，axion-core）+ 一处装配改造（003 交付物）。契约测试承载：`AgentSchedulerTest`（四坑 + 两最值钱 + 三元组 + ⑦b）+ `CliAgentConfigurationTest` 增补（⑦a poolSize）。

## ① 注册契约（registerAll）

| 输入 | 行为 |
|------|------|
| ProfileRegistry.list() 每个 Profile 的 schedules | 逐条 `taskScheduler.schedule(() -> runOnce(profile, sc), trigger)`；trigger = zone 空 ? `CronTrigger(cron)`（系统时区）: `CronTrigger(cron, TimeZone(zone))`（坑四时区显式） |
| zone 非空且非法（ZoneId.of 抛 ZoneRulesException） | 抛 `IllegalStateException`，message 含 profile 名与 zone（⑦b 不静默回退 GMT） |
| 每次 schedule 返回值 | 存入 `scheduledTasks` Map（key = 派生锁 key，⑦c 预留 28 节启停/重调度） |

## ② 触发契约（runOnce）

| 步骤 | 行为 |
|------|------|
| 1. 取锁 | 派生 key `profileName\|cron\|message`（拍板 B）→ `taskLocks.computeIfAbsent(key, ReentrantLock::new)` → `tryLock`；失败 → log.info「跳过本次触发」+ return（坑二：不排队不并行） |
| 2. 会话 | `sessionManager.getOrCreate("scheduler", "scheduler", profileName)`（宪法 VIII 三元组固定） |
| 3. 执行 | `agentService.process(session, sc.message())`——与 CLI/Web 完全同一入口；审计零新增（内部既有 llm_calls/tool_invocations 落账） |
| 4. 失败 | `catch (Exception)` → `log.error(taskKey, e)` 不外抛（坑三：调度器不死）；日志不带 message 内容（NFR-3） |
| 5. 放锁 | `finally lock.unlock()`（最值钱之二：锁必放，二进宫断言钉死） |

## ③ 装配契约（CliAgentConfiguration 改造点，003/007 交付物）

| Bean | 形态 |
|------|------|
| `ThreadPoolTaskScheduler` | `new` + `setPoolSize(4)`（⑦a：默认单线程跨任务阻塞的修复）+ `initialize()`；容器关闭随 DisposableBean 自动 shutdown |
| `AgentScheduler` | 构造注入 taskScheduler/ProfileRegistry/SessionManager/AgentService；@Bean 方法体内显式调 `registerAll()`（替代 @PostConstruct，形态适配 ②） |

## 不变式

- 任务体全程同步阻塞（宪法 VII）；ThreadPoolTaskScheduler 是陷阱表点名组件，非自建线程池禁条
- 单任务失败不影响其他任务触发（catch 边界 = runOnce 全方法）
- 前序公共接口零改动：Profile/Profile.Schedule/ProfileRegistry/SessionManager/AgentService 原样（拍板 B）
- 明确不做：28 节两张表/四端点、运行时增删改 cron、分布式协调、失败重试/告警、misfire 补跑、优雅停机、日志节流
