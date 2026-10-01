# Feature Specification: CLI 命令行入口与 Session 持久化

**Feature Branch**: `003-cli`

**Created**: 2026-09-02

**Status**: Draft

**Input**: 需求文档 docs/requirements/003-cli.md（课件第 18 节：CLI 12 子命令 + CliChannel + sessions 表 JPA 持久化 + SessionManager 换 JPA 实现 + 三运行模式）

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 终端里 init 后和 Agent 完整对话（Priority: P1）

用户在一个空目录里初始化工作区、注册 Agent，然后进入交互式对话：每说一句，Agent 经引擎思考作答，直到用户输入 `/quit` 退出。这是"看得见摸得着"的第一个入口——CLI 只做读输入、转交引擎、打印结果，自己不碰任何 Agent 逻辑。

**Why this priority**: 这是本节存在的意义（课件 §五：到这一步才能"在终端里真正跟自己搭的 Agent 说上话"）；chat 是三种运行模式里第一个落地的，也是 Demo 一对话版的入口。init（工作区）+ 装配（把 002 组件接成可运行整体）是它的前置，一并构成 MVP。

**Independent Test**: 全 mock 单测验证 chat 循环行为（读输入→转交引擎→打印、`/quit` 退出、`--profile` 缺省值、`--message` 单条退出）；人工部分真跑 `axion init` → `axion chat` 多轮对话。不依赖第 20 节工具（无工具时纯聊天即可验证循环）。

**Acceptance Scenarios**:

1. **Given** 一个已初始化的 Axion 工程且注册了至少一个 Agent，**When** 用户执行 chat 进入交互，**Then** 每行输入都转交给引擎处理并把最终答复打印回终端
2. **Given** 用户在交互中输入 `/quit`，**When** 该行被读到，**Then** 循环退出、命令正常结束
3. **Given** 用户未指定 `--profile`，**When** 启动 chat，**Then** 使用名为 default 的 Agent；指定 `--profile weather` 时使用 weather
4. **Given** 用户带 `--message "xxx"` 启动 chat，**When** 引擎返回答复，**Then** 打印答复后立即退出（不进入交互循环）
5. **Given** chat 每次拿到会话，**When** 转交引擎，**Then** 会话按 (channel="cli", 本机用户名, profile 名) 三元组取得——三元组只由会话管理器拼接成标识，命令自身不拼字符串

---

### User Story 2 - 会话跨重启恢复（Priority: P2）

用户昨天和 Agent 聊到一半，今天重启程序再次进入，同一会话的历史完整恢复，接着聊；`session list` 能看到所有会话概要。

**Why this priority**: 会话是后面所有入口共用的地基，口径问题最难查（课件 §四），需求文档 §13 把它列为验收点"Session 持久化（SQLite，跨重启恢复）"；CLI 是第一个真正"用起来" Session 的入口，持久化随本课落地。

**Independent Test**: 测试执行生产同一份手工建表脚本建 `sessions` 表，验证存、读、`messages_json` 序列化回读后消息完整（含工具调用嵌套结构）、模拟"重启"（新建上下文重查）历史还在——全单测不碰网络。

**Acceptance Scenarios**:

1. **Given** 一次多轮对话结束，**When** 会话被保存，**Then** 对话历史（含工具调用请求与结果）完整落库，可再读回
2. **Given** 进程重启后同三元组再次取会话，**When** 命中已存会话，**Then** 历史完整恢复（模拟重启由测试覆盖）
3. **Given** 多个会话已落库，**When** 用户执行 session list，**Then** 列出每个会话的标识、Agent、渠道与最后活跃时间
4. **Given** 同一三元组反复取会话，**When** 任何入口发起，**Then** 永远命中同一个会话（幂等，多轮对话靠它串起来）

---

### User Story 3 - 多 Agent 并存、按名切换互不串台（Priority: P2）

工作区里注册了多个 Agent：`profile list` 列出全部，`create` 生成新 Agent 目录，`show` 看配置概要，`delete` 移除；`chat --profile <name>` 与指定 Agent 对话，各 Agent 的会话互不相认。

**Why this priority**: 多 Agent 并存是"OS"在核心阶段的最小体现（技术方案 §8.2），Profile 管理命令是业务方定义 Agent 的第一个操作入口（需求文档 §5.1"用 profile create 生成第一个 Agent 目录"）。

**Independent Test**: 全单测/文件级验证：list 按 Agent 目录列出、create 生成模板目录、delete 移除目录；chat 按 profile 名取到正确会话（三元组隔离回归已有）。

**Acceptance Scenarios**:

1. **Given** 工作区注册了 weather 与 ops 两个 Agent，**When** 执行 profile list，**Then** 列出两者的名字
2. **Given** 执行 profile create <name>，**When** 命令完成，**Then** 生成该 Agent 的目录与配置模板；重复 create 不破坏已有内容
3. **Given** 执行 profile show <name>，**When** 命令完成，**Then** 展示该 Agent 的配置概要
4. **Given** 执行 profile delete <name>，**When** 命令完成，**Then** 该 Agent 目录被移除，其余 Agent 不受影响
5. **Given** 两个 Agent 各自进行过对话，**When** 分别以各自 profile 进入 chat，**Then** 会话历史互不相认

---

### User Story 4 - 查询类命令秒回（Priority: P3）

`init`、`profile` 四命令、`status`、`provider list`、`tool list`、`session list` 这类"看一眼就退"的命令不启动 Spring 上下文，秒级返回；只有 chat/serve/gateway 这类要跑引擎的重命令才启动 Spring。

**Why this priority**: 轻重分流是启动体验的硬要求（课件 §二：Spring Boot 启动 2~4 秒，查询命令等不起）；坑九（JPA 扫描根）是重命令启动的必经之路，不钉死会"Found 0 JPA repository interfaces"直接报错——本坑 002 人工验收已实机踩过并修复，本课回归测试钉死。

**Independent Test**: 自动化钉死坑九（重命令启动类显式声明 JPA 扫描根的架构断言）；轻命令秒回属进程级行为，人工清单验证。

**Acceptance Scenarios**:

1. **Given** 执行 status / provider list / tool list / session list，**When** 命令完成，**Then** 输出对应状态/清单，且不出现 Spring 启动日志（轻命令）
2. **Given** 执行 chat（重命令），**When** 启动日志出现，**Then** JPA 仓储扫描数大于 0（坑九：审计能落库）
3. **Given** 第 20 节工具体系尚未就位，**When** 执行 tool list，**Then** 输出占位提示说明工具归第 20 节（如实说明，不伪装）

---

### User Story 5 - 三种运行模式注册（Priority: P3）

`chat`、`serve`、`gateway` 三个运行模式命令都已注册可执行；三种模式共享同一份 Agent 配置和会话存储。serve 的 Web 服务本体与 gateway 的多通道挂载归后续节，本课两者启动后给出占位提示。

**Why this priority**: 三模式同引擎是架构承诺（需求文档 §5.10、技术方案 §8.6），命令形态（12 个、--help 可用）是 §13 验收点；serve/gateway 的完整行为依赖第 26 节，本课只立命令骨架。

**Independent Test**: 人工清单：12 个子命令逐个 `--help` 正常、serve/gateway 启动后输出占位提示不崩溃。

**Acceptance Scenarios**:

1. **Given** 执行 `axion --help` 与各子命令 `--help`，**When** 查看输出，**Then** 12 个子命令全部列出且帮助信息可用
2. **Given** 执行 serve 或 gateway，**When** 启动完成，**Then** 输出"Web Service 归第 26 节"的占位提示（本课不实现其本体）
3. **Given** chat 与 session list 共用存储，**When** chat 对话后执行 session list，**Then** 能看到该会话——运行模式之间共享同一份会话数据

---

### Edge Cases

- 未初始化的工作区直接 chat → 清晰报错提示先执行 init，不静默失败
- `--profile` 指定的 Agent 不存在 → 清晰报错（Profile 未注册），不静默用 default 顶替
- init 重复执行 → 幂等：已存在的目录与文件一律不覆盖
- 会话消息 JSON 含工具调用嵌套结构 → 序列化回读后完整（测试钉死）
- 同一三元组并发取会话 → 幂等命中同一会话（单实例假设内）
- 删除正在使用中的 Agent 目录 → 命令如实删除目录；其后 chat 该 profile 报"未注册"清晰错误
- 坑九回归：重命令启动类若丢失 JPA 扫描声明 → 架构断言测试立即红

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: 系统 MUST 提供统一命令行入口，注册 12 个子命令（init / status / chat / serve / gateway / profile list|create|show|delete / provider list / tool list / session list），参数解析统一由命令行框架处理，每个命令带帮助信息
- **FR-002**: 命令 MUST 按轻重分流：要调模型/跑引擎的重命令（chat/serve/gateway）才启动 Spring 上下文；轻命令（init、profile 四命令、status、provider list、tool list、session list）不启动 Spring，直接文件操作或直读数据库，秒级返回
- **FR-003**: 重命令启动类 MUST 显式声明持久层仓储与实体的扫描根（坑九：组件扫描根不会带动持久层扫描——不声明会扫描到 0 个仓储、审计写不进去直接报错）；该声明由架构断言测试钉死
- **FR-004**: chat 命令 MUST 实现读—转交—打印的薄壳交互：每行输入交给统一引擎处理并打印答复，`/quit` 退出；`--profile` 缺省 default；`--message` 发单条消息后退出；会话三元组为 (channel="cli", 本机用户名, profile 名)，标识拼接只发生在会话管理器一处
- **FR-005**: init 命令 MUST 幂等初始化工作区：创建完整目录结构（Agent 目录、公共技能目录、记忆目录、会话目录、日志目录、MCP 配置、引导三件套），已存在的一律不覆盖，并生成默认 Agent 模板
- **FR-006**: profile 四个命令 MUST 以 Agent 目录为真相源：list 列出全部 Agent 名；create 生成 Agent 目录与配置模板；show 展示配置概要；delete 移除目录
- **FR-007**: status / provider list / tool list / session list 四个查询命令 MUST 输出对应状态与清单；tool list 在工具体系未就位（第 20 节）时输出如实占位提示；session list 列出会话概要（标识、Agent、渠道、最后活跃时间）
- **FR-008**: 系统 MUST 交付会话持久化：会话存档表（标识主键、Agent 名、渠道、用户、序列化的对话历史、状态、创建/最后活跃/归档时间戳），对话历史整体序列化一列存储（核心阶段不按条拆表），建表走手工脚本（与既有审计表同口径）
- **FR-009**: 会话管理器 MUST 换持久化实现且契约不变：取或建（命中则反序列化历史重建会话，未命中则新建并落库）、按标识查、保存（序列化落库并更新最后活跃时间）；标识拼接仍只在该类一处（不变量四）；三触发源只提供三元组、不自己拼字符串
- **FR-010**: 重命令 MUST 有统一装配：把已交付的引擎组件（Agent 配置注册表、上下文加载、Prompt 组装、工具执行、循环、统一入口、会话管理器）接成可运行整体；Agent 配置注册表启动时扫描全部 Agent 注册
- **FR-011**: 三种运行模式命令 MUST 全部注册可执行；serve/gateway 的 Web 服务本体与多通道挂载归后续节，本课两者启动后给出占位提示
- **FR-012**: 全程 MUST 保持同步阻塞执行模型（并发由虚拟线程承载），MUST NOT 引入异步编程模型
- **FR-013**: 命令失败 MUST 给清晰报错与非零退出码，MUST NOT 静默失败
- **FR-014**: 每次 LLM 调用与工具调用 MUST 继续落审计（前序能力保证），日志 MUST 记录结构化信息且不带用户可控值

### Key Entities *(include if feature involves data)*

- **Session**（会话）：一次对话的上下文容器——标识（由渠道+用户+Agent 联合生成，拼接只此一处）、所属 Agent、渠道、用户、对话历史（前序已交付的消息形态，框架无关可序列化）
- **SessionEntity**（会话存档行）：会话的持久化形态——标识、Agent 名、渠道、用户、序列化历史、状态（活跃/归档）、三个时间戳（创建/最后活跃/归档）；本节只写活跃态，归档流转归后续节
- **Agent**（Agent 目录）：一个业务 Agent 的定义目录——配置模板与正文；profile 四命令的操作对象
- **Command**（子命令）：命令行框架的一个命令单元——名称、参数、帮助；分轻重两类（是否启动 Spring）

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 从 init 到 chat 的完整对话链路可用——人工验收中完成一次多轮对话、`/quit` 正常退出（Demo 一对话版从头走通）
- **SC-002**: 会话跨重启恢复 100% 可靠——存、读、序列化回读、模拟重启四类用例自动化钉死，`mvn clean verify` 全绿即通过
- **SC-003**: 轻命令秒级返回——init/profile list 等不出现 Spring 启动日志（人工清单）
- **SC-004**: 12 个子命令全部可执行且 `--help` 正常（人工逐个过一遍）
- **SC-005**: 重命令启动后持久层仓储扫描数大于 0（坑九实机核验，人工清单）
- **SC-006**: 三运行模式共享同一份会话存储——chat 对话后 session list 可见（人工清单）

## Assumptions

- 前序 001/002 已交付并被直接依赖：统一引擎入口、循环、Prompt 组装、工具执行、上下文加载、会话数据结构与内存版管理器（其跨节契约声明"第 18 节换持久化实现、契约不变"）、Provider 装配与审计
- 本课不交付内置工具（第 20 节）——chat 验证以无工具纯对话进行，工具就位后补完整 Demo
- serve 的 Web 服务本体与 gateway 的多通道挂载归第 26 节及后续，本课仅命令占位
- 会话标识的当前用户取本机用户名；会话归档流转（active→archived）与查询接口归第 26 节 Web Service
- 单机单实例部署假设内，不引入分布式协调
- 明确不做：认证/限流/RBAC/SSE 流式（前序已定）、对话历史按条拆表、ConfigLoader 完整形态、定时任务/通知/工具体系/记忆/沙箱实现（归各自后续节）
