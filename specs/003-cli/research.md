# Phase 0 Research: 003-cli

## R1: 轻重命令分流的落地形态

- **Decision**: 轻命令（init/status/profile×4/provider list/tool list/session list）= 纯 Picocli `Runnable`，不触发任何 Spring 类加载；`session list` 需要读库时用 `DriverManager.getConnection("jdbc:sqlite:.axion/axion.db")` 临时连接（sqlite-jdbc 经 axion-storage 传递依赖），查询 `sessions` 表——db 文件或表不存在时输出"暂无会话数据（先执行 axion init / 跑一次 chat）"而非崩溃。重命令（chat/serve/gateway）= `new SpringApplicationBuilder(AxionApplication.class).run()` 启动完整上下文，从 context 取 `CliChannel`/`AgentService` bean。
- **Rationale**: 课件 §二判断标准唯一："这个命令要不要调模型 / 跑引擎"；技术方案 §8.7"不需要 Spring 上下文的命令直接走文件操作启动快"。
- **T001 核实结论（2026-09-02，mvn dependency:tree 实测）**: axion-cli 传递可用——sqlite-jdbc 3.53.4.0（经 storage）、snakeyaml 2.4（经 core）、jackson-databind 2.21.4（经 core）、picocli 4.7.6（直接）均为 compile 级；无需新增依赖。
- **Alternatives considered**: 所有命令都起 Spring → 被否决（查询命令等 2~4 秒不可接受，课件明示）；引入懒加载单例 Spring → 被否决（复杂度超出本节边界）。

## R2: 重命令启动与装配（坑九）

- **Decision**: 重命令统一用 `AxionApplication` 作为启动类——002 fix（`bb80bf2`）已在其上显式声明 `@EnableJpaRepositories/@EntityScan(basePackages = "com.axion.storage")`，坑九防线已在位；本课新增 `CliAgentConfiguration`（@Configuration，落 axion-cli，被 `scanBasePackages = "com.axion"` 覆盖）定义 beans：`ProfileRegistry`（启动扫描 `ProfileLoader.loadAll` 注册 `.axion/agents/` 全部 Agent；工作区不存在时 WARN 并注册空表——chat 时"Profile 未注册"清晰报错）、`ContextLoader(Path.of(".axion"))`、`PromptBuilder`（注入工具集为空 Map——第 20 节 ToolRegistry 替换）、`ToolExecutor`、`ReActLoop`、`AgentService`、`SessionManager(SessionRepository)`、`CliChannel`。`ProviderService` 由 001 `ProviderConfiguration` 自动装配。坑九回归测试：反射断言启动类带两注解且 basePackages 含 `com.axion.storage`。
- **Rationale**: 课件 §二第四点"真实踩过的坑"；002 人工验收实机记录（Found 0 JPA repository interfaces → 补注解）；CLAUDE.md 依赖方向"cli 组装所有模块"。
- **Alternatives considered**: 装配放 axion-boot → 被否决（boot 是启动模块做依赖聚合，业务装配归 cli 更合"组装"语义）；每个重命令各自 new 组件 → 被否决（重复接线）。

## R3: SessionManager 换 JPA 实现（契约不变）

- **Decision**: 保留进程内 `ConcurrentHashMap<String, Session>` 缓存（活跃会话）+ `SessionRepository` 落库作持久化真相源。`getOrCreate`：缓存命中返回 → 查库命中则反序列化重建入缓存返回 → 均未命中则新建、落库、入缓存。`get`：同 getOrCreate 的查询路径。`save`：`toEntity` 序列化 messages_json + 更新 `last_active_at` 落库。转换（Session ↔ SessionEntity）是本类私有方法：core 的 `Session`（002 交付，包私有构造器同包可用）↔ `SessionEntity`（storage）。序列化用 Jackson（core 已有 jackson-databind；`Message` 全 record 形态，Jackson 2.12+ 原生支持）。
- **Rationale**: 002 跨节契约（contracts/session-manager.md）白纸黑字："第 18 节 JPA 化，契约不变、只换实现"；同三元组两次 getOrCreate 返回**同一实例**的断言（002 契约测试）由进程内缓存保证；课件 §四幂等测试断言同 id。
- **Alternatives considered**: 每次查库重建（无缓存）→ 被否决（破坏 002 契约测试"同一实例"断言）；缓存写穿但多实例共享 → 被否决（单实例假设内，需求文档 §8.2 分布式放扩展）。

## R4: sessions 表与序列化口径

- **Decision**: `sessions` 表 DDL 增量追加进 `schema.sql`（坑八口径：手工脚本唯一真相源、测试执行同一份）。列：`session_id` TEXT PK、`profile_name`、`channel`、`user_id`、`messages_json` TEXT、`status` TEXT（'active'/'archived'，默认 active）、`created_at`/`last_active_at` TEXT ISO-8601（复用 `InstantTextConverter`）、`archived_at` TEXT 可空。`messages_json` 存 `Session.messages` 的 Jackson 序列化（含 toolCall/toolResult 嵌套）。
- **Rationale**: 课件 §三"字段照技术方案 9.2、messages_json 整体序列化一列存、核心阶段不做按条拆表"；SQLite `ddl-auto=update` ALTER 弱（001/002 已立手工脚本纪律）。
- **Alternatives considered**: 按条拆表存消息 → 被否决（课件明示不做）；Hibernate 自动建表 → 被否决（坑八）。

## R5: init 目录树与默认 Profile 模板

- **Decision**: 目录树按技术方案 §8.1（用户已拍板）：`agents/`、`skills/`、`memory/`（含 MEMORY.md 模板）、`sessions/`、`logs/`、`mcp_servers.yaml`、`AGENTS.md`/`SOUL.md`/`USER.md`（Bootstrap 模板）；`axion.db` 不预建（SQLite 首连创建）。幂等：已存在一律不覆盖（需求文档 §5.1）。默认 Profile：`agents/default/AGENT.md` 模板（frontmatter：name=default、description、identity、provider(name=deepseek, model=deepseek-chat, api_key=${DEEPSEEK_API_KEY}, temperature)、tools 空、bootstrap 三件套、settings 默认值；正文占位）。
- **Rationale**: 技术方案 §8.1 + 需求文档 §5.1 幂等约定；001 `ProfileLoader` 校验 provider 引用必须命中全局层——模板用 deepseek（application.yaml 已配置）。
- **Alternatives considered**: 需求文档 §5.1 目录树（含 output/、无 mcp_servers.yaml）→ 被否决（较新文档优先：技术方案 > 需求文档，用户已拍板）。

## R6: profile 四命令与四个查询命令的数据源

- **Decision**: profile 四命令以 `.axion/agents/` 为真相源（用户拍板，宪法 IV）；`create` 生成 AGENT.md 模板（同 R5 模板、name 替换）；`show` 用 SnakeYAML 解析 frontmatter 打印概要（name/description/provider/tools/bootstrap/settings）；`delete` 递归删除目录并输出被删路径。`status` 汇总：工作区存在性、agents 数量、sessions 数（JDBC 计数，容错）、数据库位置。`provider list` 读 classpath `application.yaml` 解析 `axion.providers` 打印 name/model/base-url——**api-key 永不输出**（安全，明文零泄漏）。`tool list` 输出占位提示"内置工具归第 20 节，尚未注册"。`session list` 见 R1。
- **Rationale**: 需求文档 §5.11 命令表 + 用户拍板（10 项为实现级明确）。
- **Alternatives considered**: provider list 起 Spring 读 Environment → 被否决（违反轻重分流）；profile 读 profiles/ 目录 → 被否决（课件冲突，用户拍板按宪法 IV）。

## R7: chat 交互细节与用户标识

- **Decision**: `CliChannel`（axion-channel-cli）构造注入 `AgentService` + `SessionManager`；`chat(profileName, message)`：message 非空 → 单条处理后返回；否则循环读 stdin（`> ` 提示）、`/quit` 退出、其余行 process 并 println。`ChatCommand`（axion-cli）启动 Spring 后取 `CliChannel` bean 调用。user = `System.getProperty("user.name")`；channel 常量 `"cli"`。未初始化工作区/Profile 未注册 → 引擎/装配的清晰报错透出（不静默）。
- **Rationale**: 课件 §三骨架 + 用户拍板（--message/user 标识）；技术方案 §8.4。
- **Alternatives considered**: 交互循环写在 ChatCommand 里 → 被否决（CLAUDE.md §10：CliChannel 归 channel-cli、chat 命令实现归 channel-cli）。

## R8: serve/gateway 占位与 12 命令注册

- **Decision**: `ServeCommand`/`GatewayCommand` 注册为 @Command，启动 Spring 后打印"serve/gateway 的 Web 服务本体归第 26 节，当前为占位"并正常退出（退出码 0）；12 个子命令在 `AxionCli.subcommands` 全部注册。
- **Rationale**: 课件 §三"serve 启动 Web Service（26 节细讲）、gateway 起守护进程挂多个通道"；用户拍板占位形态；需求文档 §13"12 个命令行工具"验收点。
- **Alternatives considered**: 本课不注册 serve/gateway → 被否决（§13 验收 12 命令 + 课件交付物清单点名）。

## R9: 测试策略（课件 harness 基准）

- **Decision**: 两个测试类（课件 §四明确"值得自动化的是会话层；命令分流/--help 进程级行为人工清单"）：`SessionManagerTest` 改造为 mock `SessionRepository` 单测（幂等同实例、三元组隔离、id 拼接架构断言、save 触发落库、get 反序列化）；`SessionRepositoryTest` 真 SQLite 临时库执行手工 schema.sql（坑八）、可存可读、messages_json 回读完整（含 toolCall 嵌套）、模拟重启（新建 context 重查）。坑九架构断言（反射启动类注解）放入 axion-cli 测试。无 @Tag("integration") 冒烟——本课无网络依赖。
- **Rationale**: 课件 §四 harness 表 + 002 已立测试纪律。
- **Alternatives considered**: 为 chat 交互写 stdin 模拟测试 → 被否决（课件明示进程级行为人工清单，成本大于收益）。
