# 003-cli 代码 Review 指南

> 分支：`003-cli` | 状态：待用户 review（全量测试通过、轻命令与 chat 全链路已实跑验证）
> 生成日期：2026-09-02 | 沿用 001/002 六段格式

## 一、全景：一条命令从敲下到回复

```
axion <cmd>（boot fat jar，main = AxionCli，单二进制）
  ├─ 轻命令（init/profile×4/status/provider list/tool list/session list）
  │     └─ 零 Spring：文件操作 / JDBC 直读 .axion/axion.db（秒回）
  └─ 重命令（chat/serve/gateway）
        └─ Class.forName("com.axion.boot.AxionApplication")   ← 坑九防线所在
           + web(NONE)（不起 Tomcat）
           └─ CliAgentConfiguration 装配：
                ProfileRegistry（ProfileLoader.loadAll 扫 agents/）
                → PromptBuilder → ReActLoop → ToolExecutor → AgentService
                → SessionManager(JPA：缓存 + SessionRepository)
           └─ CliChannel.chat：读 stdin → process → println（/quit 退出）
```

数据落点：`sessions` 表（messages_json 整体序列化，spring.sql.init 启动时执行 schema.sql 建表）；
`llm_calls`/`tool_invocations` 由 002 引擎继续写入（本课无新审计旁路）。

## 二、逐文件梳理

### axion-cli（本课主角，17 个新类 + 1 个挂接改造）

| 文件 | 干什么 | 值得注意的点 |
|------|--------|-------------|
| `AxionCli.java:20-27` | 主入口挂 9 命令组（12 叶命令） | subcommands 数组是 12 命令齐全的唯一核对点（T027） |
| `ChatCommand.java:29-40` | 重命令：`Class.forName` 加载 boot 主类 + `web(NONE)` | 打破 cli↔boot 编译期循环的反射接缝（运行时 fat jar 内 boot 必在）；`--profile` 默认 default、`--message` 单条退出 |
| `ServeCommand/GatewayCommand.java` | 占位：启动 Spring 后打印"归第 26 节"退出 0 | 同样 `web(NONE)`——占位阶段不抢 8080 |
| `CliAgentConfiguration.java:46-101` | 重命令装配（本课粘合 002 的胶水） | 工作区缺失 → WARN + 空 ProfileRegistry（chat 时报"Profile 未注册"清晰错）；工具集空 Map 留第 20 节替换位；ProviderService 由 001 装配自动就位 |
| `InitCommand.java` | 幂等建 `.axion/` 目录树 + default Agent 模板 | **实跑抓过 bug**：agents/default 父目录必须先建（`:62-64`）；模板 api_key 占位 `${DEEPSEEK_API_KEY}` 不明文 |
| `ProfileCreateCommand.java` | 生成 agents/\<name>/AGENT.md 模板 | 与 InitCommand 模板同口径（name 替换）；已存在不覆盖 |
| `ProfileShowCommand.java` | SnakeYAML 解析 frontmatter 打印概要 | **api-key 只显示占位**（不解析环境变量）——凭证零泄漏 |
| `ProfileDeleteCommand.java` | 递归删除目录 | 不存在清晰报错非零退出 |
| `ProfileListCommand.java` | 扫 agents/ 目录列名 | 宪法 IV 口径（课件 profiles/ 冲突经用户拍板取 agents/） |
| `StatusCommand.java` | 工作区/Agent 数/会话数/库位置 | JDBC 计数容错（表未就绪显示"未就绪"不崩） |
| `ProviderListCommand.java` | 读 classpath application.yaml 列 provider | **只打 name/model/base-url**；`getOrDefault` 模式匹配写法（`:25-28`） |
| `SessionListCommand.java` | JDBC 直读 sessions 概要 | 库/表不存在输出提示不崩溃（轻命令容错原则） |
| `ToolListCommand.java` | 占位提示"工具归第 20 节" | 如实说明，不伪装 |
| 4 个分组父命令（Profile/Session/Provider/ToolCommand） | Picocli 嵌套结构件 | 子命令名重复（三个 list）迫使分组；已补列交付清单 |

### axion-channel-cli（首份内容）

| 文件 | 干什么 | 值得注意的点 |
|------|--------|-------------|
| `CliChannel.java:32-50` | 读—转交—打印交互循环 | **编码跟随终端**（`terminalCharset()`：console charset 优先、defaultCharset 兜底——003 实跑踩过 GBK 乱码）；`/quit` 是唯一自判断逻辑；channel 常量 `"cli"`、user 取本机用户名；EOF 退出（管道输入友好） |

### axion-storage / axion-core（会话持久化）

| 文件 | 干什么 | 值得注意的点 |
|------|--------|-------------|
| `SessionEntity.java` | sessions 表 JPA 实体 | 字段照技术方案 §9.2 九列；`updateHistory` 只刷 messages_json + last_active_at（created_at/status 保留）；时间戳复用 InstantTextConverter |
| `SessionRepository.java` | JpaRepository\<SessionEntity, String> | 只读写由 SessionManager 收口 |
| `SessionManager.java:36-94`（core，002 改造点） | 内存版 → JPA 版，**契约不变** | 双真相：ConcurrentHashMap 缓存（保证 002"同实例"契约断言）+ 落库（跨重启恢复）；getOrCreate 恢复路径不重复落库；转换收口本类私有方法；`sessionIdOf` 仍全仓库唯一（H4） |
| `schema.sql` 增量 | sessions 表 DDL | 坑八口径：手工脚本唯一真相源 |

### 打包与配置（实跑修复的工程缺口）

| 文件 | 改动 | 为什么 |
|------|------|--------|
| `axion-boot/pom.xml` | spring-boot-maven-plugin 加 `mainClass=com.axion.cli.AxionCli` | 单二进制：整个程序的 main 是 CLI（技术方案 §8.7）；boot 聚合一切资源（application.yaml 供轻命令读） |
| `axion-cli/pom.xml` | repackage 加 `<skip>true</skip>` | 实跑踩坑：cli 嵌套 fat jar 进 boot 后 BOOT-INF 套 BOOT-INF，类加载失败 |
| `axion-boot/application.yaml` | 加 `spring.sql.init.mode=always + schema-locations` | 001 review 留白"建表脚本无人执行"到期；SQLite 不被视为 embedded 必须显式 always；DDL 全幂等 |
| `.gitignore` | 加 `.axion/` | 运行时工作区不入库 |

### 测试（3 个类，坑↔测试对号）

| 测试类 | 钉死的坑 |
|--------|---------|
| `SessionManagerTest`（改造，9 用例） | 幂等同实例+同 id；三元组隔离；id 公式；**get 反序列化重建（跨重启语义）**；恢复路径不重复落库；save 保留 created_at；H4 架构断言；不可变视图 |
| `SessionRepositoryTest`（2 用例） | 坑八：手工 schema.sql 建表九列真实存在；messages_json 含 toolCall 嵌套回读完整；**模拟重启**新建连接重查历史还在 |
| `JpaScanConfigurationTest`（boot，1 用例） | **坑九回归**：启动类必须带 `@EnableJpaRepositories`/`@EntityScan` 且 basePackages 含 com.axion.storage |

## 三、重点 review 清单（按风险排序）

1. **`SessionManager.java:36-94`（002 契约兑现）**——问自己：换 JPA 后，002 的"同三元组两次 getOrCreate 同一实例"契约靠什么保证？（答：进程内缓存）缓存与落库有没有写穿不一致的路径？（getOrCreate 恢复不重复落库、save 保留 created_at 均有测试钉死）
2. **`ChatCommand.java:29-40`（坑九 + 循环破解）**——`Class.forName` 是编译期循环的破解点：运行时 boot 类是否必然在 classpath？（单二进制 fat jar 保证）`web(NONE)` 有没有漏掉其他重命令？
3. **`CliChannel.java` 终端编码**——`terminalCharset()` 的优先级（console charset > defaultCharset）与 `@SuppressWarnings` 理由是否成立；回退路径（重定向 stdin）是否覆盖 `--message` 之外的场景。
4. **`CliAgentConfiguration.java` 装配完整性**——缺工作区/缺 Agent/缺 Provider 三种失败模式的报错是否清晰（WARN+空表 → AgentService 报"Profile 未注册" → ProviderConfiguration 报"缺少 api-key"）；第 20 节替换工具集的接缝是否留好（空 Map 注入点）。
5. **凭证零泄漏**——`ProviderListCommand`/`ProfileShowCommand` 是否任何路径都不输出 api-key 值（占位原样展示）；全局 grep 无明文 key。
6. **打包三件套**——boot mainClass / cli skip / spring.sql.init：三处都是实跑炸出来的，review 时确认注释与配置一致，防回退。

## 四、刻意留白（review 时不要当成缺陷报）

- serve/gateway 只有占位提示——Web 本体归第 26 节、多通道归后续节（课件明示）
- tool list 输出占位——ToolRegistry/内置工具归第 20 节
- chat 无内置工具时模型如实说"无法联网"——预期行为，http_get 就位后 Demo 一完整版补验
- SessionManager 的进程内缓存不跨实例——单机单实例假设内（分布式协调放扩展）
- sessions 表只有 active 状态流转——归档归第 26 节
- 会话缓存与落库的最终一致性——单进程内顺序写，无并发窗口（单实例假设）
- `provider list` 输出 model=null——全局层 application.yaml 未配 model（model 属 Profile 层，001 设计）

## 五、建议 review 顺序

按一次 chat 的生命周期读：**CliChannel（10 分钟）→ ChatCommand + 打包三件套（10 分钟）→ CliAgentConfiguration（10 分钟）→ SessionManager + SessionEntity（15 分钟）→ 其余 13 个命令类扫读（15 分钟）→ 三个测试类对号（10 分钟）**。

重点文件三个：`SessionManager.java`（契约兑现）、`CliAgentConfiguration.java`（粘合胶水）、`CliChannel.java`（编码教训）。打包三件套看 diff 而非全文。

## 六、当前验收状态

- 机器判卷：`mvn clean verify` 全绿（55 tests + spotless/errorprone -Werror/SpotBugs/FindSecBugs/P3C/checkstyle）
- 人工（2026-09-02 实跑）：轻命令全链（init 幂等/profile 四命令/status/provider list/tool list/session list/--help 12 命令）、chat 中文多轮对话 + /quit、**跨进程历史恢复铁证**（新进程复述上一进程会话）、session list 时间戳正确、坑九 Found 3 仓储、provider list 零 key 泄漏
- 遗留：http_get 就位（第 20 节）后补跑 Demo 一完整版（工具调用版）
