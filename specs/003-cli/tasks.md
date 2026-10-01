# Tasks: CLI 命令行入口与 Session 持久化

**Input**: Design documents from `specs/003-cli/`（plan.md / spec.md / data-model.md / contracts/ / research.md / quickstart.md）

**Prerequisites**: plan.md ✓、spec.md ✓（五个 user story）、data-model.md ✓、contracts/ ✓（cli-commands / session-persistence）、research.md ✓（R1~R9）

**Tests**: 已明确要求（课件第 18 节验收 harness：SessionManagerTest + SessionRepositoryTest + 坑九架构断言；命令分流/--help 属进程级行为入人工清单）

**Organization**: 按 user story 分组，每个 story 独立可测、可独立交付。

## 格式约定

- `[P]`：可并行（不同文件、无依赖）
- `[USx]`：归属 user story；Setup/Foundational/Polish 不标
- 所有文件路径为仓库根目录相对路径，包名 `com.axion.*`

---

## Phase 1: Setup（共享基础设施）

**Purpose**: 依赖核实

- [X] T001 依赖核实（写前 H3 门禁）：对 axion-cli 跑 `mvn dependency:tree`，核实 ① sqlite-jdbc 经 axion-storage 传递可用（轻命令 JDBC 直读）② SnakeYAML/Jackson 经 axion-core 传递可用（profile show 解析 / messages 序列化）③ Picocli 版本与 BOM 锁定一致；结论记录到 `specs/003-cli/research.md` 的 R1/R3 备注，核实不到 → 停下报告

---

## Phase 2: Foundational（阻塞性前置）

**Purpose**: 会话持久化地基（sessions 表 + 实体 + 仓储 + SessionManager 换 JPA 实现），完成前不得开始任何 story

- [X] T002 [P] 在 `axion-storage/src/main/resources/schema.sql` **增量追加** `sessions` DDL（`session_id` TEXT PK、`profile_name`、`channel`、`user_id`、`messages_json`、`status`、`created_at`/`last_active_at` TEXT ISO-8601、`archived_at` 可空；字段见 data-model.md）
- [X] T003 [P] 创建 `SessionEntity` 在 `axion-storage/src/main/java/com/axion/storage/SessionEntity.java`——JPA 实体，`session_id` 主键，时间戳复用 `InstantTextConverter`（字段见 data-model.md）
- [X] T004 [P] 创建 `SessionRepository` 在 `axion-storage/src/main/java/com/axion/storage/SessionRepository.java`——`JpaRepository<SessionEntity, String>`
- [X] T005 [P] **harness 先行**：编写 `SessionRepositoryTest` 在 `axion-storage/src/test/java/com/axion/storage/SessionRepositoryTest.java`——**坑八口径**：测试执行手工 schema.sql 建表；可存可读；`messages_json` 回读后消息完整（含 toolCall/toolResult 嵌套）；**模拟"重启"**（新建 context 重查历史还在）（先写、先跑、确认失败）
- [X] T006 [P] **harness 先行**：改造 `SessionManagerTest` 在 `axion-core/src/test/java/com/axion/core/SessionManagerTest.java`——mock `SessionRepository`：同三元组两次 getOrCreate 返回**同一实例**（幂等）；channel/user/profile 任一不同则不同 Session；**session_id 拼接只此一处**（H4 架构断言保留）+ Session 无公开构造器断言保留；save 触发 repository 落库；get 反序列化重建（先改、先跑、确认失败）
- [X] T007 实现 `SessionManager` JPA 化在 `axion-core/src/main/java/com/axion/core/SessionManager.java`——**契约不变**（getOrCreate/get/save 签名逐字保留，002 跨节契约）：进程内 ConcurrentHashMap 缓存 + `SessionRepository` 落库（getOrCreate：缓存→查库反序列化→新建落库；save：messages 序列化 + last_active_at 更新）；Session↔SessionEntity 转换收口本类私有方法；session_id 拼接仍只此一处

**Checkpoint**: 会话持久化地基就绪——五个 story 可开始

---

## Phase 3: User Story 1 - 终端里 init 后和 Agent 完整对话（Priority: P1）★ MVP

**Goal**: init 幂等建工作区 + 装配把 002 组件接成 beans + chat 读—转交—打印交互（/quit、--profile、--message）

**Independent Test**: `mvn test` 全绿（SessionManagerTest/SessionRepositoryTest/坑九断言）+ 人工 `axion init` → `axion chat` 多轮对话（quickstart 人工项）

### Tests for US1（harness 先行）

- [X] T008 [P] [US1] 编写**坑九架构断言**在 `axion-boot/src/test/java/com/axion/boot/JpaScanConfigurationTest.java`——反射断言启动类 `AxionApplication` 带 `@EnableJpaRepositories`/`@EntityScan` 且 basePackages 含 `com.axion.storage`（先写、先跑、确认失败或缺失）

### Implementation for US1

- [X] T009 [P] [US1] 实现 `InitCommand` 在 `axion-cli/src/main/java/com/axion/cli/InitCommand.java`——建 `.axion/` 目录树（agents/skills/memory(含 MEMORY.md 模板)/sessions/logs + mcp_servers.yaml + AGENTS.md/SOUL.md/USER.md 模板，研究 R5 口径）；**幂等不覆盖**；生成 `agents/default/AGENT.md` 模板（provider=deepseek + ${DEEPSEEK_API_KEY} 占位）
- [X] T010 [P] [US1] 实现 `CliAgentConfiguration` 在 `axion-cli/src/main/java/com/axion/cli/CliAgentConfiguration.java`——@Configuration 装配 beans：ProfileRegistry（启动扫描 `ProfileLoader.loadAll` 注册 `.axion/agents/`，工作区缺失 WARN + 空表）、ContextLoader、PromptBuilder（工具集空 Map，第 20 节替换）、ToolExecutor、ReActLoop、AgentService、SessionManager（JPA 版）、CliChannel（研究 R2/R7 口径）
- [X] T011 [P] [US1] 实现 `CliChannel` 在 `axion-channel-cli/src/main/java/com/axion/channel/cli/CliChannel.java`——构造注入 AgentService + SessionManager；`chat(profileName, message)`：message 非空单条处理后返回；否则 `> ` 提示循环读 stdin、`/quit` 退出、其余行 process + println；user=本机用户名、channel="cli"（课件 §三骨架）
- [X] T012 [US1] 实现 `ChatCommand` 在 `axion-cli/src/main/java/com/axion/cli/ChatCommand.java`——`@Command(name="chat")` + `--profile`(默认 default) + `--message`；`SpringApplicationBuilder(AxionApplication.class).run()` 启动（坑九防线在启动类）→ 取 CliChannel bean → 调用；`run()` 后关闭 context
- [X] T013 [US1] 在 `axion-cli/src/main/java/com/axion/cli/AxionCli.java` 的 `subcommands` 挂接 `init`/`chat`（其余命令后续 phase 逐个挂上，Polish 核对 12 个齐全）

**Checkpoint**: US1 独立可测——init → chat 全链路（MVP）

---

## Phase 4: User Story 2 - 会话跨重启恢复（Priority: P2）

**Goal**: 会话落库可读可查——session list 命令列出会话概要（持久化本体已在 Foundational 钉死）

**Independent Test**: `SessionRepositoryTest`（含模拟重启）全绿 + 人工：chat 对话后 session list 可见、重启进程后历史恢复

### Implementation for US2

- [X] T014 [P] [US2] 实现 `SessionListCommand` 在 `axion-cli/src/main/java/com/axion/cli/SessionListCommand.java`——轻命令 JDBC 直读 `.axion/axion.db` 的 sessions 表输出概要（session_id/profile/channel/最后活跃时间）；库或表不存在时输出"暂无会话数据（先 init / 跑一次 chat）"不崩溃（研究 R1 口径）
- [X] T015 [US2] 在 `AxionCli.java` 挂接 `session list`

**Checkpoint**: US1+US2 独立可测——跨重启恢复 + 会话可查

---

## Phase 5: User Story 3 - 多 Agent 并存、按名切换互不串台（Priority: P2）

**Goal**: profile 四命令以 `.axion/agents/` 目录为真相源（宪法 IV，用户拍板）

**Independent Test**: 人工清单：list/create/show/delete 逐个跑通、chat --profile 切换、会话隔离（会话隔离由 SessionManagerTest 三元组断言钉死）

### Implementation for US3

- [X] T016 [P] [US3] 实现 `ProfileListCommand` 在 `axion-cli/src/main/java/com/axion/cli/ProfileListCommand.java`——扫 `.axion/agents/` 子目录列名（缺工作区提示先 init）
- [X] T017 [P] [US3] 实现 `ProfileCreateCommand` 在 `axion-cli/src/main/java/com/axion/cli/ProfileCreateCommand.java`——生成 `agents/<name>/AGENT.md` 模板（与 InitCommand 默认模板同口径，name 替换）；已存在提示不覆盖
- [X] T018 [P] [US3] 实现 `ProfileShowCommand` 在 `axion-cli/src/main/java/com/axion/cli/ProfileShowCommand.java`——SnakeYAML 解析 frontmatter 打印概要（name/description/provider/tools/bootstrap/settings，**api-key 占位不输出明文**）；不存在清晰报错
- [X] T019 [P] [US3] 实现 `ProfileDeleteCommand` 在 `axion-cli/src/main/java/com/axion/cli/ProfileDeleteCommand.java`——递归删除目录并输出被删路径；不存在清晰报错
- [X] T020 [US3] 在 `AxionCli.java` 挂接 `profile list/create/show/delete`

**Checkpoint**: US1~US3 独立可测——多 Agent 管理闭环

---

## Phase 6: User Story 4 - 查询类命令秒回（Priority: P3）

**Goal**: status / provider list / tool list 三个轻命令（session list 已在 US2）秒级返回、零 Spring 启动

**Independent Test**: 人工清单：三命令秒回无 Spring 日志；provider list 输出不含 api-key

### Implementation for US4

- [X] T021 [P] [US4] 实现 `StatusCommand` 在 `axion-cli/src/main/java/com/axion/cli/StatusCommand.java`——汇总：工作区存在性、Agent 数（agents 目录计数）、会话数（JDBC 计数，容错）、数据库位置（研究 R6 口径）
- [X] T022 [P] [US4] 实现 `ProviderListCommand` 在 `axion-cli/src/main/java/com/axion/cli/ProviderListCommand.java`——轻命令读 classpath `application.yaml` 解析 `axion.providers` 打印 name/model/base-url；**api-key 永不输出**（研究 R6 口径）
- [X] T023 [P] [US4] 实现 `ToolListCommand` 在 `axion-cli/src/main/java/com/axion/cli/ToolListCommand.java`——输出占位提示"内置工具归第 20 节，尚未注册"
- [X] T024 [US4] 在 `AxionCli.java` 挂接 `status`/`provider list`/`tool list`

**Checkpoint**: US1~US4 独立可测——查询秒回 + 凭证零泄漏

---

## Phase 7: User Story 5 - 三种运行模式注册（Priority: P3）

**Goal**: serve/gateway 命令占位注册；12 子命令齐全、--help 全可用

**Independent Test**: 人工清单：12 个 --help 逐个过、serve/gateway 输出占位提示不崩溃

### Implementation for US5

- [X] T025 [P] [US5] 实现 `ServeCommand` 在 `axion-cli/src/main/java/com/axion/cli/ServeCommand.java`——重命令启动 Spring 后输出"serve 的 Web 服务本体归第 26 节，当前为占位"，退出码 0
- [X] T026 [P] [US5] 实现 `GatewayCommand` 在 `axion-cli/src/main/java/com/axion/cli/GatewayCommand.java`——同上占位（多通道挂载归后续节）
- [X] T027 [US5] 在 `AxionCli.java` 挂接 `serve`/`gateway` 并**核对 12 子命令齐全**（init/status/chat/serve/gateway/profile×4/provider list/tool list/session list）

**Checkpoint**: 12 命令全部注册——§13 验收点"12 个命令行工具"就位

---

## Phase 8: Polish & Cross-Cutting Concerns

**Purpose**: 全量门禁与收尾证据

- [X] T028 全量验证：`mvn clean verify` 全绿（含静态检查门禁）+ 执行 quickstart.md 人工清单（init→profile create→chat 多轮/quit、轻命令秒回、Found N > 0、跨重启恢复、12 --help、provider list 无 key）；反作弊红线：不删断言、不加 `@Disabled`、不放宽阈值
- [X] T029 [P] 六条全局不变量自查并记录证据：① 涉外 IO 过 Sandbox.enforce（本课无涉外 IO 工具；接线位维持第 20 节）② LLM/工具审计延续落库（chat 链路经 002 引擎，坑九断言保证仓储可写）③ grep 无明文 key（含 provider list 输出路径）④ session_id 只在 SessionManager 拼接 ⑤ 无异步模型/自建线程池 ⑥ 无 Spring AI 自动执行路径

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: 无依赖，立即开始
- **Foundational (Phase 2)**: 依赖 Setup——**阻塞全部 user story**（sessions 表 + SessionManager JPA 是全部命令的地基）
- **US1 (Phase 3)**: 依赖 Foundational → **MVP**；无对其他 story 的依赖
- **US2 (Phase 4)**: 依赖 Foundational（session list 读库）
- **US3 (Phase 5)**: 依赖 Foundational（AGENT.md 模板与 InitCommand 同口径，T009 先于 T017）
- **US4 (Phase 6)**: 依赖 Foundational
- **US5 (Phase 7)**: 依赖 Foundational（重命令启动 Spring 形态与 ChatCommand 同款，T012 先于 T025/T026）
- **Polish (Phase 8)**: 依赖全部 story

### User Story Dependencies

- US1（P1）：Foundational 后即可开始 → **MVP**
- US2（P2）：Foundational 后即可开始，独立可测
- US3（P2）：Foundational 后即可开始（模板口径依赖 T009），独立可测
- US4（P3）：Foundational 后即可开始，独立可测
- US5（P3）：Foundational 后即可开始，独立可测

### Within Each User Story

- 测试 MUST 先写并确认失败，再写实现（harness 先行）
- 实现完成即跑该模块测试，红了当场修（任务级 DoD）

### Parallel Opportunities

- Phase 2 的 T002~T006 可并行（T007 等 T005/T006 先红）
- US1 的 T008~T011 可并行（T012 依赖 T010/T011，T013 依赖 T012）
- US2~US5 的命令类各自 [P]，同一 phase 内并行；挂接任务串行

---

## Implementation Strategy

### MVP First (US1 Only)

1. Phase 1 Setup → 2. Phase 2 Foundational（关键，阻塞一切）→ 3. Phase 3 US1 → **停下来独立验证**（mvn test 全绿 + 人工 init→chat 对话）

### Incremental Delivery

- 地基（Phase 1+2）→ US1（MVP：能聊起来）→ US2（跨重启恢复）→ US3（多 Agent 管理）→ US4（查询秒回）→ US5（12 命令齐全）→ Polish 全量门禁

---

## Notes

- **执行纪律（axion-spec）**：每个 task 开始前跑宪法 9 条速查 + 停止清单预检；任务级 DoD 后**不自动 commit / push / 运行 package.sh**——同步时机由用户决定（本模板原"Commit after each task"条款被本纪律覆盖）
- 测试方法名英文（中文语义用 `@DisplayName` 保留）
- 反作弊红线：测试未全绿不得宣称完成
- 停止清单第 1/2 条判断基准 = 需求文档「交付清单」白名单：AxionCli（挂接）、12 个 @Command 类、CliChannel、SessionEntity/SessionRepository、SessionManager（JPA 改造，签名不变）、CliAgentConfiguration、sessions 表 schema.sql 增量、SessionManagerTest（改造）/SessionRepositoryTest/JpaScanConfigurationTest、约定四条（轻重分流/坑九声明/default 缺省/cli 常量）——清单之外的对外概念一律停下报告
- **改造点（用户已拍板）**：SessionManager 实现换 JPA——只改实现与构造依赖，`getOrCreate`/`get`/`save` 签名逐字不变（002 跨节契约）
