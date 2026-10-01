# Research: 008-scheduler（技术选型与裁决记录）

> 本 feature 无未决 NEEDS CLARIFICATION——全部裁决已在需求文档钉死（2026-09-07：修订说明 ①~⑦，含课件口径拍板、锁 key 拍板 B、实施前优化 ⑦a~d 分类）。本文件记录裁决内容与备选，作为 plan/tasks 的依据。

## 裁决 1：调度机制——ThreadPoolTaskScheduler 动态注册（坑一）

- **Decision**: `ThreadPoolTaskScheduler.schedule(Runnable, CronTrigger)` 启动时逐条注册；不用静态 `@Scheduled`（cron 写死注解里改一次重编译，违背"配置即 Agent"）
- **Rationale**: 技术方案 §8.5 明文 + 课件 §二「别自己写调度器」；触发规则按 Profile 配置动态生成，编译期注解做不到
- **Alternatives considered**: `@Scheduled`（否决：配置驱动失效）；自研调度器（否决：重复造轮子）

## 裁决 2：锁 key 派生（用户拍板 B，2026-09-07）

- **Decision**: 不改 `Profile.Schedule`（002 已交付 cron/zone/message 三字段，CLAUDE.md frontmatter 三键已定）；AgentScheduler 内部派生锁 key `profileName|cron|message`
- **Rationale**: 课件 ScheduleConfig.id 与现状无 id 的形态适配——拍板 B 前序零改动、波及面最小；进程内锁 key 足够稳定
- **Alternatives considered**: 给 Schedule 加 id 字段（否决：改 002 字面量，波及 AgentLoader 与测试构造点）；**28 节 task_id 来源必须重议**（⑦d 注记：message 是自然语言、改一字重启即 key 漂移——届时候选：给 Schedule 补 id / 换 profileName+cron+zone 稳定派生）

## 裁决 3：调度池大小 setPoolSize(4)（⑦a，P1 修复）

- **Decision**: 装配处 `setPoolSize(4)`——默认单线程下同步阻塞的长 ReAct 占住唯一调度线程，跨任务互相拖累（四文档之外盲点：防重叠锁只管同任务）
- **Rationale**: 多 Agent 多任务是 Axion 定位形态；任务体同步（宪法 VII）无需大池，2~4 足够
- **Alternatives considered**: 默认 poolSize=1（否决：跨任务串行阻塞）；大池（否决：任务体同步，无 IO 密集并行需求）

## 裁决 4：zone 合法性校验（⑦b，P1 修复）

- **Decision**: 注册时 `ZoneId.of(zone)` 校验，非法 → 包装带 profile/zone 的 IllegalStateException 启动报错
- **Rationale**: `TimeZone.getTimeZone` 对拼错时区静默回退 GMT → 「到点不触发」（陷阱表变体）；001 ConfigLoader 纪律「非法配置不静默」延伸
- **Alternatives considered**: 静默按 GMT（否决：到点不触发且无任何提示）；AgentLoader 解析时校验（可行，但注册处校验离消费点更近、AgentSchedulerTest 单测可覆盖）

## 裁决 5：ScheduledFuture 句柄保存（⑦c，P2 预留）

- **Decision**: `schedule` 返回值存 `ConcurrentMap<String, ScheduledFuture<?>>`，key = 派生锁 key
- **Rationale**: 28 节「启用停用」与扩展阶段重调度需要 cancel/重排句柄；ThreadPoolTaskScheduler 无公开"列已注册任务"API，不存则 28 节返工 registerAll
- **Alternatives considered**: 不存（否决：28 节返工）；值对象封装 ScheduledTaskHandle（28 节若 list 端点需要再升级，类内部改动无契约影响）

## 裁决 6：装配与生命周期（FR-5）

- **Decision**: CliAgentConfiguration 加 ThreadPoolTaskScheduler @Bean（new + setPoolSize(4) + initialize，容器关闭自动 shutdown——DisposableBean 语义）+ AgentScheduler @Bean（构造注入四依赖）+ @Bean 方法体内显式调 registerAll（替代 @PostConstruct，形态机械适配 ②）
- **Rationale**: G4-C1 无组件注解（005/007 拍板延续）；registerAll 显式调用保证装配时注册一次
- **Alternatives considered**: @Component + @PostConstruct（否决：形态拍板）；InitializingBean（否决：多一个接口实现，@Bean 方法内直调最简）

## 裁决 7：失败与审计口径（坑三）

- **Decision**: runOnce catch Exception → log.error（任务派生 key + 异常栈，不带 message 内容——NFR-3）→ 不外抛；审计零新增（AgentService 内部既有路径落 llm_calls/tool_invocations）
- **Rationale**: 调度器是常驻基础设施，单任务失败不得拖垮线程池；钟推与人推同一条审计链（宪法 VIII 兑现）
- **Alternatives considered**: 单独钟推审计表（否决：违背"不为钟推新设任何概念"）；失败重试/告警（否决：扩展阶段，明确不做已列）
