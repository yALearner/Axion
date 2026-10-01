# Tasks: ReAct Runtime（第一周：对接 LLM + ReAct 循环）

**Input**: Design documents from `/specs/001-react-runtime/`（plan.md / spec.md / research.md / data-model.md / contracts/ / quickstart.md）

**Prerequisites**: 已完成 clarify + plan。依赖升级清单见 plan.md "Parent POM 变更清单"；关键 API 形态见 research.md R1-GA / R2 / R3。

**Tests**: 包含——spec 的 SC-003 / SC-004 / SC-006 / SC-008 明确要求自动化用例，quickstart.md 列出确定性用例清单。真实 Provider 用例为独立 profile（缺 API key 环境变量时跳过）。

**Organization**: 按用户故事分阶段；每个故事可独立实现、独立验证。

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 可并行（不同文件、无未完成依赖）
- **[Story]**: US1~US4（对应 spec.md 四个用户故事）

---

## Phase 1: Setup（共享基础设施：依赖升级与模块接线）

**Purpose**: 父 POM 升级到 GA 线（Boot 3.5.16 + Spring AI 1.1.8），9 模块按 plan.md 依赖方向接线，全局配置与日志基建就位。

- [ ] T001 环境准备 + 父 POM 变更 in `pom.xml`：
  - **环境准备**（已实测：本机 JDK 21 Temurin `C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot` + Maven 3.9.16 `D:\tools\apache-maven-3.9.16` 可用，但 PATH/JAVA_HOME 未配置、`mvnw` 脚本缺失）：
    - 实施会话内每次构建前执行：`export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot"`、`export PATH="$JAVA_HOME/bin:/d/tools/apache-maven-3.9.16/bin:$PATH"`，并以 `/d/tools/apache-maven-3.9.16/bin/mvn -version` 验证（应显示 Java version 21.x + Maven 3.9.16）
    - 用户侧永久配置（可选，由用户执行）：系统环境变量加 `JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot`、PATH 追加 `D:\tools\apache-maven-3.9.16\bin`（或 VS Code 设 `maven.executable.path`）
  - **网络注意**：`~/.m2/settings.xml` 阿里云镜像只代理 central（实测可达）；升级后无 milestone 依赖，全部走 central 镜像
  - **POM 变更**：`spring-boot-starter-parent` 3.3.5→3.5.16、`spring-ai.version` 1.0.0-M4→1.1.8、移除 `spring-ai-alibaba` 属性与依赖、删除 `spring-milestones` 仓库、依赖管理新增 `net.logstash.logback:logstash-logback-encoder:8.0` 与 `org.hibernate.orm:hibernate-community-dialects`（无版本）、删除 sqlite-jdbc 显式 3.45.3.0（跟随 BOM 3.49.1.0）
- [ ] T002 [P] axion-storage 依赖：`spring-boot-starter-data-jpa` + `org.xerial:sqlite-jdbc` + `org.hibernate.orm:hibernate-community-dialects` in `axion-storage/pom.xml`
- [ ] T003 [P] axion-provider 依赖：`org.springframework.ai:spring-ai-starter-model-openai` in `axion-provider/pom.xml`
- [ ] T004 [P] axion-core 依赖：Spring AI chat API artifact（GA BOM 内 `spring-ai-model` + `spring-ai-chat-model`，实施时以 `mvn dependency:tree` 核对 GA 实际 artifact 名）+ SnakeYAML in `axion-core/pom.xml`
- [ ] T005 [P] axion-tool 依赖：axion-core + Spring AI（`@Tool` 注解所在 artifact，同 T004 核对）+ test scope `org.wiremock:wiremock-standalone:3.x`（T045 用）in `axion-tool/pom.xml`
- [ ] T006 [P] axion-channel-cli 依赖：axion-core in `axion-channel-cli/pom.xml`
- [ ] T007 [P] axion-cli 依赖：picocli + snakeyaml + 组装 axion-storage/core/provider/tool/channel-cli in `axion-cli/pom.xml`
- [ ] T008 [P] SQLite 数据源与 JPA 配置：`spring.datasource.url=jdbc:sqlite:.axion/axion.db?foreign_keys=on&journal_mode=wal&busy_timeout=5000`、`hibernate.dialect=org.hibernate.community.dialect.SQLiteDialect`、`ddl-auto=none`、`spring.sql.init.mode=always`、Hikari `maximum-pool-size=4`、`spring.jpa.open-in-view=false` in `axion-cli/src/main/resources/application.properties`（research.md R2）
- [ ] T009 [P] logback-spring.xml：控制台纯文本 + JSON 滚动文件 `logs/axion.jsonl`（LogstashEncoder，`includeMdcKeyName=true`）+ `server` profile 下 JSON 上 stdout in `axion-cli/src/main/resources/logback-spring.xml`（research.md R3）
- [ ] T010 构建验证：`mvn clean package` 全模块编译通过（升级后无依赖冲突；输出 `dependency:tree` 抽查 spring-ai 版本均为 1.1.8）

**Checkpoint**: 骨架在 GA 依赖线上可构建，基础配置就位。

---

## Phase 2: Foundational（阻断性前置：核心领域模型 + 配置加载 + 存储 + 接口契约）

**Purpose**: 所有用户故事共享的核心抽象与基建。**本阶段未完成前任何用户故事不得开工。**

- [ ] T011 [P] Message 记录（role/content/toolCalls/toolCallId，channel 无关内部表示）in `axion-core/src/main/java/com/axion/core/message/Message.java`
- [ ] T012 [P] ToolResult 记录（success/content/errorMessage/retryable）in `axion-core/src/main/java/com/axion/core/tool/ToolResult.java`
- [ ] T013 [P] AxionTool 统一 Tool 抽象接口（getName/getDescription/getInputSchema/execute）in `axion-core/src/main/java/com/axion/core/tool/AxionTool.java`
- [ ] T014 [P] Profile 记录（name/description/identity/provider/tools/channels/sandbox.allowed_domains/settings，字段与默认值按 data-model.md §1）in `axion-core/src/main/java/com/axion/core/profile/Profile.java`
- [ ] T015 WorkspacePaths（`.axion/` 各子路径常量）in `axion-core/src/main/java/com/axion/core/config/WorkspacePaths.java`
- [ ] T016 SessionManager（内存会话：session_id = channel+user+profile 联合生成、消息按序追加、**Q1 截断规则** recentHistory()——保留最近 N 条用户消息及其后全部 assistant/tool 消息、不切断工具链；channel=`cli`、user=`user.name`）in `axion-core/src/main/java/com/axion/core/session/SessionManager.java`
- [ ] T017 [P] Sandbox 接口（`enforce(SandboxAction)`，接口先行不携带实现细节）+ SandboxAction + ActionType 枚举（FILE_READ/FILE_WRITE/SHELL_COMMAND/HTTP_REQUEST）in `axion-tool/src/main/java/com/axion/tool/sandbox/`
- [ ] T018 [P] ToolRegistry 接口（注册/按名查找）in `axion-core/src/main/java/com/axion/core/tool/ToolRegistry.java`
- [ ] T019 [P] LlmCallEntity（字段按 data-model.md §5：provider/model/prompt_tokens/completion_tokens/total_tokens/duration_ms/created_at；ID 用 `GenerationType.IDENTITY`，Instant 用 AttributeConverter 存 ISO-8601 TEXT）in `axion-storage/src/main/java/com/axion/storage/entity/LlmCallEntity.java`
- [ ] T020 [P] ToolInvocationEntity（tool_name/input_json/result_json/success/error_message/duration_ms，同上）in `axion-storage/src/main/java/com/axion/storage/entity/ToolInvocationEntity.java`
- [ ] T021 [P] LlmCallRepository（Spring Data JPA）in `axion-storage/src/main/java/com/axion/storage/repo/LlmCallRepository.java`
- [ ] T022 [P] ToolInvocationRepository（Spring Data JPA）in `axion-storage/src/main/java/com/axion/storage/repo/ToolInvocationRepository.java`
- [ ] T023 schema.sql（两表 `CREATE TABLE IF NOT EXISTS` 幂等 DDL，字段按 data-model.md §5）in `axion-storage/src/main/resources/schema.sql`
- [ ] T024 ConfigLoader（SnakeYAML 解析 `.axion/config.yaml` sandbox 段 + `${ENV_VAR}` 占位解析；缺失/非法给出指明具体变量名的报错；文件不存在 = 合法未配置态）in `axion-cli/src/main/java/com/axion/cli/config/ConfigLoader.java`
- [ ] T025 AgentLoader（扫描 `.axion/agents/`、`deriveProfile(agentDir)` 解析 frontmatter、结构校验：缺 name/正文为空 → 错误日志 + 该 Agent 不可用 + 不阻断其他 Agent）in `axion-core/src/main/java/com/axion/core/loader/AgentLoader.java`

**Checkpoint**: 领域模型、配置加载、审计存储、接口契约齐备——四个用户故事可并行开工。

---

## Phase 3: User Story 1 - 配置 Provider 并通过 CLI 多轮对话 (Priority: P1) 🎯 MVP

**Goal**: 环境变量配 key、Agent 目录声明 provider 后，`axion chat` 多轮连贯对话；显式 provider 映射；配置错误零静默。

**Independent Test**: 手写一个 `AGENT.md`（provider + 正文），执行 `axion chat --profile <name>` 完成 3 轮以上连贯对话（FakeChatModel 自动化 + 真实 Provider 手动各一）。

### Implementation for User Story 1

- [ ] T026 [US1] ContextLoader（AGENT.md 正文 + 当前日期时间组装 system prompt）in `axion-core/src/main/java/com/axion/core/context/ContextLoader.java`
- [ ] T027 [US1] PromptBuilder（FR-012 顺序：system → 对话历史（SessionManager.recentHistory()）→ 可用 Tool 列表（ToolRegistry 提供，空列表合法）→ 转 Spring AI Message）in `axion-core/src/main/java/com/axion/core/react/PromptBuilder.java`
- [ ] T028 [US1] ProviderConfig（显式构建 OpenAiChatModel Bean：base-url 规则按 research.md R1-GA——DeepSeek `https://api.deepseek.com`、Kimi `https://api.moonshot.cn`（不带 /v1）；api key 从解析后的环境变量取）in `axion-provider/src/main/java/com/axion/provider/config/ProviderConfig.java`
- [ ] T029 [US1] ProviderService（`Map<String, ChatModel>` 显式映射，宪法原则三：不扫描容器 Bean 类型；按 provider.name 解析）in `axion-provider/src/main/java/com/axion/provider/ProviderService.java`
- [ ] T030 [US1] ReActLoop 基础路径（组装 Prompt → `ChatModel.call` → 无 tool 调用即返回最终响应 → 响应追加 Session；每次 LLM 调用写 `llm_calls` 审计**并输出结构化日志一行**——MDC session_id + kv: provider/model/tokens/duration_ms，research.md R3；审计写失败记错误日志不阻断对话；LLM 返回空内容或无法解析 → 报错并提示重试，不输出空回复）in `axion-core/src/main/java/com/axion/core/react/ReActLoop.java`
- [ ] T031 [US1] AgentService.process()（三入口共用引擎入口，宪法原则八：CLI 消息统一汇入，ReActLoop 不感知入口）in `axion-core/src/main/java/com/axion/core/agent/AgentService.java`
- [ ] T032 [US1] CliChannel（交互循环：读 stdin → AgentService.process → 输出最终回复；LLM 调用失败显示明确信息、进程不崩溃可继续输入）in `axion-channel-cli/src/main/java/com/axion/channel/cli/CliChannel.java`
- [ ] T033 [US1] chat 命令（`--profile <name>` 指定 Agent；未指定时恰好 1 个 Agent 默认使用并提示、0 个或多个报错并列出可用 Agent）in `axion-cli/src/main/java/com/axion/cli/commands/ChatCommand.java`
- [ ] T034 [US1] 扩展 AgentLoader 校验：provider 必须存在于 ProviderService 映射、api_key 占位解析失败给出指明环境变量名的报错（FR-003/FR-018，SC-006）
- [ ] T035 [US1] AxionCli 主入口：非 Web Spring 上下文（`SpringApplicationBuilder`，web-application-type NONE）启动 chat 路径 in `axion-cli/src/main/java/com/axion/cli/AxionCli.java`
- [ ] T036 [P] [US1] FakeChatModel 测试替身（脚本化响应序列：给定消息即返回预设 AssistantMessage，含多轮与 tool 调用注入能力）in `axion-core/src/test/java/com/axion/core/testutil/FakeChatModel.java`
- [ ] T037 [P] [US1] 多轮对话测试（US-1 场景 1/2：3 轮连贯 + 前文引用，FakeChatModel）in `axion-core/src/test/java/com/axion/core/react/ReActLoopBasicTest.java`
- [ ] T038 [P] [US1] 配置错误测试（SC-006：缺 API key / provider 不存在 / frontmatter 非法 → 指明具体变量或 provider 的报错，0 静默）in `axion-cli/src/test/java/com/axion/cli/config/ConfigValidationTest.java`
- [ ] T039 [US1] 手动验证：quickstart.md 场景 1（真实 Provider 多轮对话，SC-001 从 init 到首句 ≤5 分钟）。注意：US3 完成前用**手工创建** `.axion/agents/<name>/AGENT.md` + `.axion/config.yaml` 替代 init/profile 命令，T067 再用完整命令流程复核

**Checkpoint**: `axion chat` 可用真实 LLM 多轮对话（MVP）。

---

## Phase 4: User Story 2 - Agent 通过 ReAct 循环自主调用 HTTP Tool (Priority: P1)

**Goal**: Agent 自主调用 `http_get` 获取外部数据并基于真实数据作答；Sandbox 白名单 fail-closed；审计两表完整落库；max_iterations 防挂死。

**Independent Test**: 让 Agent 完成"查询公开 API 并总结"任务（天气），观察自主发起 Tool 调用并基于返回数据作答，全程零干预（SC-002）。

### Implementation for User Story 2

- [ ] T040 [US2] ToolRegistryImpl（`ToolCallbacks.from(@Tool Beans)` 生成 schema 并注册 name→ToolCallback 映射；AxionTool 视图从 ToolDefinition 派生，执行委托 ToolCallback.call；扩展 AgentLoader 校验：Profile.tools 各条目必须已注册，未注册记错误日志、该 Agent 不可用——FR-018）in `axion-tool/src/main/java/com/axion/tool/registry/ToolRegistryImpl.java`
- [ ] T041 [US2] HttpTools（`@Tool` 注解的 http_get：2xx→success；4xx/3xx 等非 2xx 直接失败 retryable=false；5xx 内部自动重试 ≤3 次、间隔 500ms/1s/2s 线性退避，耗尽后 success=false + retryable=true；content 始终带状态码与响应体；Java `java.net.http.HttpClient` 实现）in `axion-tool/src/main/java/com/axion/tool/http/HttpTools.java`
- [ ] T042 [US2] WhitelistSandbox（HTTP 域名通配匹配：有效白名单 = Agent 级声明 ? Agent 级 : 全局（覆盖语义）；两级均未配置 fail-closed 拒绝一切外网；匹配失败返回带原因的失败结果）in `axion-tool/src/main/java/com/axion/tool/sandbox/WhitelistSandbox.java`
- [ ] T043 [US2] ToolExecutor（按名查找 → 参数存在性/基本合法性校验 → Sandbox.enforce 校验 → 执行 → 包装 ToolResult → 写 `tool_invocations` 审计（含失败）**并输出结构化日志一行**——kv: tool_name/duration_ms/success，research.md R3；未注册 Tool 返回带错误信息的失败结果回填对话）in `axion-core/src/main/java/com/axion/core/tool/ToolExecutor.java`
- [ ] T044 [US2] ReActLoop 完整循环（tool 调用分支：解析 `AssistantMessage.getToolCalls()` → ToolExecutor 执行 → `ToolResponseMessage` 回填 → 继续循环；`ToolCallingChatOptions.internalToolExecutionEnabled(false)` 确认关闭 Spring AI 内部执行；max_iterations 达上限强制结束并清晰提示；审计写失败不阻断）in `axion-core/src/main/java/com/axion/core/react/ReActLoop.java`（扩展 T030）
- [ ] T045 [P] [US2] http_get 状态码语义测试（WireMock：2xx 成功 / 404 直接失败不重试 / 500 重试 3 次后失败且 retryable=true；状态码+响应体回填）in `axion-tool/src/test/java/com/axion/tool/http/HttpToolsTest.java`
- [ ] T046 [P] [US2] WhitelistSandbox 测试（Agent 级覆盖全局 / 未声明继承全局 / 两级未配置 fail-closed / `*.example.com` 通配匹配）in `axion-tool/src/test/java/com/axion/tool/sandbox/WhitelistSandboxTest.java`
- [ ] T047 [US2] ReAct 三种终止条件测试（SC-003：无 Tool 调用直接返回 / Tool 链完成后返回 / max_iterations 强制结束——FakeChatModel 脚本化）in `axion-core/src/test/java/com/axion/core/react/ReActLoopToolTest.java`
- [ ] T048 [P] [US2] Tool 错误回填测试（US-2 场景 5/6：未注册 Tool 名称、白名单拒绝 → 带错误信息的失败结果回填，Agent 可修正）in `axion-core/src/test/java/com/axion/core/tool/ToolErrorHandlingTest.java`
- [ ] T049 [P] [US2] 审计落库测试（SC-008：每次 LLM/Tool 调用含失败各一条；SQLite `:memory:` + `maximum-pool-size=1` + `ddl-auto=create-drop`；另附生产 schema.sql 校验用例——`mode=always` + `ddl-auto=none` 同一内存库）in `axion-storage/src/test/java/com/axion/storage/AuditPersistenceTest.java`
- [ ] T050 [US2] 手动验证：quickstart.md 场景 2（天气 Demo，SC-002）+ 场景 4（白名单拒绝）+ 场景 6（sqlite3 抽查审计行）。同 T039 说明：US3 完成前手工创建 Agent 目录与配置

**Checkpoint**: 第一周核心 Demo（Agent 自主调 http_get）跑通。

---

## Phase 5: User Story 3 - 初始化工作区并管理多个 Agent 目录 (Priority: P2)

**Goal**: `axion init` 幂等建工作区；profile 四命令管理 Agent 目录；多 Agent 并存互不影响、非法目录不阻断他人。

**Independent Test**: `axion init` 后 `profile create` 创建两个声明不同 provider 的 Agent，分别 `--profile` 对话，各自按自己的配置运行（SC-007）。

### Implementation for User Story 3

- [ ] T051 [US3] init 命令（幂等创建 `.axion/`：agents/skills/memory/logs 目录 + AGENTS.md/SOUL.md/USER.md；已存在内容一律不覆盖，重复执行提示已初始化）in `axion-cli/src/main/java/com/axion/cli/commands/InitCommand.java`
- [ ] T052 [P] [US3] AGENT.md 最小模板资源（frontmatter + 正文占位，字段按 contracts/config.md）in `axion-cli/src/main/resources/templates/agent-template.md`
- [ ] T053 [US3] profile 子命令组：create（生成模板、同名报错）/ list（表格输出，非法目录标"不可用"不中断）/ show / delete in `axion-cli/src/main/java/com/axion/cli/commands/profile/`
- [ ] T054 [US3] 扩展 AgentLoader 校验：channels 条目必须为已支持 Channel（本里程碑仅 cli），校验失败记错误日志不阻断启动（FR-018）
- [ ] T055 [P] [US3] init 幂等性测试（重复执行不覆盖已有内容）in `axion-cli/src/test/java/com/axion/cli/commands/InitCommandTest.java`
- [ ] T056 [P] [US3] profile 命令测试（create/list/show/delete + US-3 场景 4：非法 frontmatter 不阻断其他 Agent 使用）in `axion-cli/src/test/java/com/axion/cli/commands/ProfileCommandsTest.java`
- [ ] T057 [US3] 手动验证：quickstart.md 场景 5（配置错误零静默）+ 场景 7（多 Agent 并存不串号，SC-007）

**Checkpoint**: 工作区与 Agent 目录管理闭环，多 Agent 并存验证通过。

---

## Phase 6: User Story 4 - CLI 多轮会话与会话上下文管理 (Priority: P2)

**Goal**: 历史累积与截断行为完整验证；`--message` 单条调用后退出；`/quit` 退出；单次消息内部开销 ≤50ms。

**Independent Test**: chat 内连续对话超过 `max_history_turns`，验证按配置截断且最近轮次连贯；`--message` 单条返回后进程退出。

### Implementation for User Story 4

- [ ] T058 [US4] chat 命令扩展：`--message "..."` 单条消息输出回复后进程退出（exit 0）；交互模式 `/quit` 退出（exit 0）in `axion-cli/src/main/java/com/axion/cli/commands/ChatCommand.java`（扩展 T033）
- [ ] T059 [US4] 空消息处理（不触发 LLM 调用、友好提示、继续等待输入）in `axion-channel-cli/src/main/java/com/axion/channel/cli/CliChannel.java`（扩展 T032）
- [ ] T060 [P] [US4] 历史截断测试（US-4 场景 2：超过 max_history_turns 仅注入最近 N 轮、system prompt 始终保留、工具调用链不被切断——Q1 规则）in `axion-core/src/test/java/com/axion/core/session/HistoryTruncationTest.java`
- [ ] T061 [P] [US4] `--message` / `/quit` / 空消息行为测试（进程退出码与提示语）in `axion-channel-cli/src/test/java/com/axion/channel/cli/CliChannelTest.java`
- [ ] T062 [US4] 单次消息处理开销测试（SC-005：除 LLM 调用外 ≤50ms，FakeChatModel 计时）in `axion-core/src/test/java/com/axion/core/react/ProcessingOverheadTest.java`
- [ ] T063 [US4] 手动验证：quickstart.md 场景 3（--message 单条模式）

**Checkpoint**: Session 语义（累积/截断/两种退出形态）完整可验证。

---

## Phase 7: Polish & Cross-Cutting Concerns

**Purpose**: 查询命令、SC-004 抽测、全场景走查。

- [ ] T064 [P] provider list 命令（name/model/baseUrl，API key 脱敏不输出）in `axion-cli/src/main/java/com/axion/cli/commands/ProviderListCommand.java`
- [ ] T065 [P] tool list 命令（ToolRegistry 已注册 Tool 的 name/description 摘要）in `axion-cli/src/main/java/com/axion/cli/commands/ToolListCommand.java`
- [ ] T066 SC-004 自动化抽测用例（10 组 ≥3 轮多轮对话、引用正确 ≥9/10；真实 Provider，缺 API key 环境变量时跳过；10 组提示词内置测试内，覆盖自我介绍/偏好/纠错类场景）in `axion-cli/src/test/java/com/axion/cli/MultiTurnSamplingTest.java`
- [ ] T067 全量构建 `mvn clean package` 全绿；quickstart.md 七个场景全走查一遍（SC-001~SC-008 对照表勾验）
- [ ] T068 [P] README/项目文档更新：第一周命令用法与 Demo 截图/说明 in `README.md`
- [ ] T069 [P] Provider 显式映射路由测试（SC-007：测试内定义两个脚本化 ChatModel 桩分别注册为两个 provider，断言各自按配置命中、不串号——宪法原则三）in `axion-provider/src/test/java/com/axion/provider/ProviderRoutingTest.java`

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: 无依赖，立即开工
- **Foundational (Phase 2)**: 依赖 Setup——**阻断所有用户故事**
- **User Stories (Phase 3~6)**: 均依赖 Foundational 完成；按优先级 P1→P2 顺序推进（US1、US2 为 P1）
- **Polish (Phase 7)**: 依赖各故事完成

### User Story Dependencies

- **US1**: Foundational 后即可开工，不依赖其他故事（agent 目录可先手写）
- **US2**: 依赖 US1 的 ReActLoop 基础路径（T030）与 ProviderService；独立测试不依赖 US3/US4
- **US3**: 与 US1/US2 无硬依赖（AgentLoader 在 Foundational），但 profile 命令使 US1 的验证流程自动化
- **US4**: 依赖 US1 的 chat/CliChannel 基础（T032/T033）与 Foundational 的 SessionManager

### Within Each User Story

- 模型/接口 → 服务 → 循环/命令 → 测试 → 手动验证
- 测试任务（[P]）在对应实现完成后即可并行

### Parallel Opportunities

- Phase 1: T002~T009 全部可并行（不同 pom/资源文件）
- Phase 2: T011~T014、T017~T023 可并行（不同文件）；T016/T024/T025 相互独立
- Phase 3: T036~T038 测试可并行；T028→T029 顺序，其余 [P] 文件并行
- Phase 4: T045/T046/T048/T049 可并行；T041/T042 可并行
- Phase 5: T052/T055/T056 可并行
- Phase 6: T060/T061 可并行

---

## Parallel Example: User Story 2

```bash
# 实现组（并行，不同模块不同文件）:
Task: "T041 [US2] HttpTools ... in axion-tool/.../http/HttpTools.java"
Task: "T042 [US2] WhitelistSandbox ... in axion-tool/.../sandbox/WhitelistSandbox.java"

# 测试组（并行，不同测试类）:
Task: "T045 [P] [US2] WireMock http_get 语义测试"
Task: "T046 [P] [US2] WhitelistSandbox 测试"
Task: "T049 [P] [US2] 审计落库测试"
```

---

## Implementation Strategy

### MVP First（Setup + Foundational + US1）

1. Phase 1 依赖升级 → `mvn clean package` 绿
2. Phase 2 领域模型/配置/存储/接口
3. Phase 3 US1 → **STOP AND VALIDATE**：真实 Provider 多轮对话（quickstart 场景 1）
4. 此时即第一周可演示的最小成果

### Incremental Delivery

1. Setup + Foundational → 骨架就绪
2. + US1 → 可对话 MVP 🎯
3. + US2 → 第一周核心 Demo（天气查询，硬条件）
4. + US3 → 工作区/多 Agent 管理
5. + US4 → Session 语义完整
6. + Polish → SC-004 抽测与文档

### Parallel Team Strategy

- 单人按序推进：Setup → Foundational → US1 → US2 → US3 → US4 → Polish
- 多人：Foundational 完成后，A 做 US1+US2（同主线），B 做 US3，C 做 US4 测试

---

## Notes

- [P] 任务 = 不同文件、无未完成依赖
- 每个故事完成后跑其 Independent Test 再进入下一故事
- 每个任务或逻辑组完成后 commit
- **环境**：本机 JDK/Maven 未在 PATH——任何构建任务前先按 T001 环境准备步骤 export JAVA_HOME/PATH 并验证 `mvn -version`
- GA artifact 名核对（T004/T005）：`spring-ai-model` / `spring-ai-chat-model` 以 1.1.8 BOM 实际 artifact 为准，用 `mvn dependency:tree` 验证
- 宪法红线提醒：任何代码不得使用 `ChatClient`（自动 tool 执行）；tool 调度只在 ReActLoop + ToolExecutor（T044 必须验证 `internalToolExecutionEnabled(false)`）
