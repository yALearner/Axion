# Tasks: Memory 三层记忆（会话 + 长期）

**Input**: `specs/006-memory/` 设计文档（plan.md / spec.md / research.md / data-model.md / contracts/memory-service.md / quickstart.md）
**需求文档**: `docs/requirements/006-memory.md`（交付清单 §本节交付物清单为比对基准）
**Tests**: 显式要求——spec 各 US 的 Independent Test + 需求文档「验收标准·自动化部分」测试分层表 + 宪法质量门（每功能模块至少一个端到端测试）。**harness 先行**：每 phase 测试任务先于对应实现任务。

**组织方式**: 按 user story 分组（US1→US4 依优先级），实现与测试一起落地。

## 格式约定

- `[P]`：可并行（不同文件、无未完成依赖）
- `[USn]`：所属 user story；Setup/Foundational/Polish 无 story 标签
- 所有路径相对仓库根；任务描述含精确文件路径

---

## Phase 1: Setup（共享基础设施）

**目的**：axion-memory 空壳就绪 + 测试骨架落位

- [x] T001 补齐 `axion-memory/pom.xml` 依赖：`axion-storage`（SqliteMemoryStore 用 MemoryEntryRepository）、`spring-web`（Mem0 档 RestClient）、`spring-boot-starter-test` + `mockwebserver`（test scope）——全部为项目既有依赖，**零新第三方**（参照 `axion-tool/pom.xml`）
- [x] T002 [P] 创建测试目录骨架 `axion-memory/src/test/java/com/axion/memory/`（空包 + 后续测试类落位）

**Checkpoint**: 模块可编译，`mvn test -pl axion-memory -am` 空跑绿

---

## Phase 2: Foundational（阻塞全部 user story 的前置）

**目的**：core 依赖倒置端口（001 LlmGateway 先例）——三件接口/枚举，后续所有 story 依赖

- [x] T003 [P] 创建 `MemoryScope` 枚举 in `axion-core/src/main/java/com/axion/core/MemoryScope.java`（`CORE` / `ARCHIVAL`——坑十七：写哪区由 Agent 显式声明）
- [x] T004 [P] 创建 `LongTermMemoryStore` 接口 in `axion-core/src/main/java/com/axion/core/LongTermMemoryStore.java`（`append(content, scope)` / `load()` / `recallByKeyword(keyword)`；javadoc 写死四条行为契约：坑十五不缓存 / 坑十六核心永不截断 / 坑十七 scope 显式 / 坑十八检索只搜归档）
- [x] T005 [P] 创建 `MemoryService` 接口 in `axion-core/src/main/java/com/axion/core/MemoryService.java`（`buildContext(Session)` / `remember(content, scope)` / `recall(keyword)`——统一门面，上层零实现细节）

**Checkpoint**: 接口墙就位——user story 实现可以开始

---

## Phase 3: User Story 1 - 跨对话记偏好 (Priority: P1) ★ MVP

**Goal**: save_memory 写入 → 重启/新会话 → 新一轮 prompt 注入记忆、Agent 可引用作答（Demo 二对话版机器可判部分）

**Independent Test**: 全 mock 单测验证 MemoryService 委托链与两 Tool；`mvn test -pl axion-memory,axion-core -am` 绿

### Tests for User Story 1（先行，先红后绿）

- [x] T006 [P] [US1] `MarkdownMemoryStoreTest` 基础契约 in `axion-memory/src/test/java/com/axion/memory/MarkdownMemoryStoreTest.java`（`@TempDir` 临时文件）：坑十五写后立读（append 后同实例 load 立即命中）、坑十七 scope 路由正确区块、坑十八 recallByKeyword 只搜归档区、未命中返回空
- [x] T007 [P] [US1] `MemoryToolsTest` in `axion-memory/src/test/java/com/axion/memory/MemoryToolsTest.java`：scope 缺省写 archival、scope 非法值明确报错、SaveMemory 成功返回"已记住"、RecallMemory 未命中返回"没有找到相关记忆"不抛异常、content/keyword 必填校验（005 S1 口径）；**审计落账断言（宪法 V 显式任务）**：经 ToolExecutor 执行 save_memory/recall_memory 后 `tool_invocations` 落账 success 正确
- [x] T008 [P] [US1] `MemoryServiceTest` in `axion-memory/src/test/java/com/axion/memory/MemoryServiceTest.java`：buildContext = 核心记忆 + 会话历史的组合、归档区不整体注入；remember/recall 正确委托 LongTermMemoryStore（接口墙——不碰 SessionManager 之外的实现细节）
- [x] T009 [US1] 改造 `axion-core/src/test/java/com/axion/core/PromptBuilderTest.java`（002 交付物测试）：构造器新增 MemoryService 参数、system prompt 组装含 buildContext 输出（核心记忆 + 会话历史）断言
- [x] T010 [US1] 新增 `CliAgentConfigurationTest` 装配断言 in `axion-cli/src/test/java/com/axion/cli/CliAgentConfigurationTest.java`（**2026-09-06 用户指示补强**：cli 模块此前零测试——005 先例留白，本次补齐）＋ 同步给 `axion-cli/pom.xml` 增 `spring-boot-starter-test`（test scope，项目既有依赖）。用 `ApplicationContextRunner` 最小上下文（mock `SessionRepository`/`ToolInvocationRepository`/`NotifyChannelRepository`/`ProviderService`，Mockito）断言：① `SaveMemoryTool`/`RecallMemoryTool` 已注册进 ToolRegistry（`contains("save_memory")`/`contains("recall_memory")`）② `MemoryService` bean 存在且注入 PromptBuilder（对 promptBuilder bean 调用可见 buildContext 输出）③ 缺省 backend → `LongTermMemoryStore` bean 为 `MarkdownMemoryStore` 实例；**换档断言（sqlite/mem0/非法值）属 US3 T025 增补**

### Implementation for User Story 1

- [x] T011 [P] [US1] 实现 `MarkdownMemoryStore` 基础读写 in `axion-memory/src/main/java/com/axion/memory/MarkdownMemoryStore.java`（implements LongTermMemoryStore）：MEMORY.md 两 header 分区（`## 核心记忆`/`## 归档记忆`，003 init 已建模板）、append 按 scope 写对应区块（条目 `- [日期] 内容` 前缀）、load 每次 `Files.readString` 重读（坑十五）、recallByKeyword 归档区 `String.lines().filter(contains)` 朴素匹配（坑十八）；**截断逻辑（坑十六）与并发互斥/原子写属 US2 任务，此阶段不实现**
- [x] T012 [P] [US1] 实现 `SaveMemoryTool` in `axion-memory/src/main/java/com/axion/memory/SaveMemoryTool.java`（implements AxionTool 纯实现，005 机械适配：手写 JsonSchema、无组件注解）：content 必填、scope 可选（core/archival 缺省 archival、非法值明确报错——坑十七）、成功返回"已记住"；只依赖 `MemoryService` 接口
- [x] T013 [P] [US1] 实现 `RecallMemoryTool` in `axion-memory/src/main/java/com/axion/memory/RecallMemoryTool.java`（implements AxionTool 纯实现）：keyword 必填、未命中返回"没有找到相关记忆"不抛异常、命中换行拼接；只依赖 `MemoryService` 接口
- [x] T014 [US1] 实现 `MemoryServiceImpl` in `axion-memory/src/main/java/com/axion/memory/MemoryServiceImpl.java`（implements MemoryService）：buildContext 会话历史委托 `SessionManager`、长期记忆委托 `LongTermMemoryStore`；remember/recall 直接委托；异常上抛不吞
- [x] T015 [US1] 改造 `PromptBuilder` in `axion-core/src/main/java/com/axion/core/PromptBuilder.java`（002 交付物，需求文档「改造点」明列）：构造器新增 `MemoryService` 参数；system prompt 组装调 `buildContext(session)`（每次重新读不缓存——坑十五联动）
- [x] T016 [US1] 装配改造 `CliAgentConfiguration` in `axion-cli/src/main/java/com/axion/cli/CliAgentConfiguration.java`：`MarkdownMemoryStore` @Bean + `MemoryServiceImpl` 装配 + 两 Tool 注册进 `ToolRegistry` + MemoryService 注入 PromptBuilder；**此阶段 memory.backend 固定 markdown（换档装配属 US3 T032）**
- [x] T017 [US1] 跑 `mvn test -pl axion-memory,axion-core,axion-cli -am` 全绿（US1 完成判据）

**Checkpoint**: US1 独立可测——全 mock 单测绿；人工部分（Demo 二对话版真模型）留待 Polish 阶段清单

---

## Phase 4: User Story 2 - 核心记忆始终在场 (Priority: P2)

**Goal**: 核心区永不截断、截断只裁归档区（坑十六）；并发追加零丢失（双层互斥 + 原子写——FR-3 并发与原子写约定）

**Independent Test**: 最值钱回归测试——灌 500 条归档流水 → load 含核心条目、不含最早归档、含最近归档；50 虚拟线程并发各 append 一条 → load 全部命中

### Tests for User Story 2（先行，先红后绿）

- [x] T018 [US2] `MarkdownMemoryStoreTest` 增补**截断回归** in `axion-memory/src/test/java/com/axion/memory/MarkdownMemoryStoreTest.java`：核心区写 1 条 + 归档区灌 500 条流水 → load 含核心条目一字不少、不含最早归档（`归档流水 0`）、含最近归档（`归档流水 499`）——4000 字阈值只裁归档尾部（需求文档「最值钱回归测试」原文用例）；**另增 FR-6 故障路径断言（S5-C2 修复）**：文件不可读 → append/load 异常上抛不静默（快速失败，不返回空记忆）
- [x] T019 [US2] `MarkdownMemoryStoreTest` 增补**并发回归** in 同文件：50 虚拟线程各 append 一条 → load 全部命中零丢失（双层互斥 + 原子写的回归钉）

### Implementation for User Story 2

- [x] T020 [US2] `MarkdownMemoryStore` 截断逻辑 in `axion-memory/src/main/java/com/axion/memory/MarkdownMemoryStore.java`：load 时归档区超 4000 字只裁尾部（坑十六）；**截断函数只接收归档段——核心区物理上动不到**（代码结构保证）
- [x] T021 [US2] `MarkdownMemoryStore` 并发与原子写 in 同文件（FR-3 约定）：append 双层互斥（进程内 `synchronized` + 跨进程 `FileChannel.lock()`，JDK 原生零依赖）+ **锁内重读**（读-改-写全程互斥，不基于旧内容覆盖）；写回用临时文件（UUID 名）+ `Files.move(ATOMIC_MOVE, REPLACE_EXISTING)`（同盘原子替换，任何时刻磁盘上要么旧文件要么新文件）；load 免锁（原子写保证读不到半写）；append 失败异常上抛（不静默"已记住"，由 ToolExecutor 审计 success=false）

**Checkpoint**: US1+US2 都独立可测——截断与并发回归全绿

---

## Phase 5: User Story 3 - 记忆量增长的平滑升级 (Priority: P2)

**Goal**: 三档后端一次交付（SqliteMemoryStore / Mem0MemoryStore）+ `axion.memory.backend` 一行换档，上层（PromptBuilder/MemoryTools）零改动——接口墙价值兑现

**Independent Test**: 参数化契约测试遍历三档钉死四条行为契约（行为等价性）；mem0 档 mock HTTP 层验证协议翻译；`mvn test -pl axion-memory,axion-storage -am` 绿

### Tests for User Story 3（先行，先红后绿）

- [x] T022 [P] [US3] `LongTermMemoryStoreTest` **参数化契约测试** in `axion-memory/src/test/java/com/axion/memory/LongTermMemoryStoreTest.java`：遍历 Markdown/Sqlite/Mem0 三档实现钉死四条行为契约（坑十五不缓存 / 坑十六核心不截断 / 坑十七 scope 路由 / 坑十八只搜归档）——三档行为等价性防线（自审 #5）
- [x] T023 [P] [US3] `SqliteMemoryStoreTest` in `axion-memory/src/test/java/com/axion/memory/SqliteMemoryStoreTest.java`：手工 schema.sql 建表（坑八口径——测试与生产同一份脚本）；append→INSERT、load→CORE 全量 + ARCHIVAL 倒序 LIMIT、recall→LIKE 仅归档；语义与 Markdown 档一致；**另增 FR-6 故障路径断言（S5-C2 修复）**：库损坏/不可读 → 异常上抛不静默
- [x] T024 [P] [US3] `Mem0MemoryStoreTest` in `axion-memory/src/test/java/com/axion/memory/Mem0MemoryStoreTest.java`：**mock HTTP 层**（MockWebServer 假 Mem0 服务，005 先例）：append/load/recall 的 REST 翻译（add/get/search 请求路径/方法/参数映射）、非 2xx 异常上抛不吞、凭证占位解析
- [x] T025 [US3] `CliAgentConfigurationTest` 增补**换档断言** in `axion-cli/src/test/java/com/axion/cli/CliAgentConfigurationTest.java`（T010 同文件增量）：`backend=sqlite` → `LongTermMemoryStore` bean 为 `SqliteMemoryStore`；`backend=mem0` → `Mem0MemoryStore`（凭证占位经 Environment 注入）；**backend=非法值 → 上下文启动失败且异常消息明确不静默**（FR-6，001 ConfigLoader 口径）

### Implementation for User Story 3

- [x] T026 [P] [US3] 创建 `MemoryEntry` 实体 in `axion-storage/src/main/java/com/axion/storage/MemoryEntry.java`（`id` PK AUTOINCREMENT / `content` / `scope` / `created_at` ISO-8601 TEXT——复用 `InstantTextConverter`；手写 getter 无 Lombok、无 setter 收口）
- [x] T027 [P] [US3] 创建 `MemoryEntryRepository` in `axion-storage/src/main/java/com/axion/storage/MemoryEntryRepository.java`（Spring Data JPA，005 模式机械延伸）
- [x] T028 [P] [US3] `schema.sql` 手工增量 in `axion-storage/src/main/resources/schema.sql`：追加 `memory_entries` 建表（坑八口径——不依赖 ddl-auto 自动迁移）
- [x] T029 [US3] 实现 `SqliteMemoryStore` in `axion-memory/src/main/java/com/axion/memory/SqliteMemoryStore.java`（implements LongTermMemoryStore）：append→INSERT、load→`scope='CORE'` 全量 + `scope='ARCHIVAL'` 按 created_at 倒序 LIMIT（截断语义对应）、recall→`LIKE '%keyword%'` 仅 ARCHIVAL；复用已有 SQLite（零外部依赖）
- [x] T030 [US3] 实现 `Mem0MemoryStore` in `axion-memory/src/main/java/com/axion/memory/Mem0MemoryStore.java`（implements LongTermMemoryStore）：`RestClient` 直连自托管 Mem0（005 Boot builder + timeout 装配先例）；append/load/recall 翻译 add/get/search；核心/归档分区语义落 metadata（**metadata 分区 key 名称随 H3 协议核实一并钉死并记录——S5-U1 修复**）；凭证与地址 `${MEM0_BASE_URL}`/`${MEM0_API_KEY}` 环境变量占位（001 口径，自托管应 HTTPS）；**H3 实施前核实**自托管版 REST 协议（方法/路径/参数）——核实不到 → 停止清单第 5 条停下报告；非 2xx 异常上抛不吞
- [x] T031 [US3] `application.yaml` 新增配置键 in `axion-boot/src/main/resources/application.yaml`：`axion.memory.backend`（取值 `markdown`/`sqlite`/`mem0`，缺省 `markdown`）+ mem0 凭证占位注释（需求文档「配置形态示例」逐字）
- [x] T032 [US3] `CliAgentConfiguration` 换档装配 in `axion-cli/src/main/java/com/axion/cli/CliAgentConfiguration.java`：按 `axion.memory.backend` 显式 @Bean 装配对应 LongTermMemoryStore 实现（宪法 III 哲学——不扫描）；**非法值启动校验明确报错不静默**（001 ConfigLoader 口径）；后端故障快速失败不自动降级
- [x] T033 [US3] `MemoryEntryRepositoryTest` in `axion-storage/src/test/java/com/axion/storage/MemoryEntryRepositoryTest.java`（005 storage 先例：每实体一 RepositoryTest；schema.sql 手工建表 + INSERT/查询往返）⚠️ **超出需求文档测试清单字面，属软停点比对"多"项，用户已确认保留**

**Checkpoint**: 三档实现齐 + 契约测试钉死等价性；换档 = 一行配置

---

## Phase 6: User Story 4 - USER.md 与 MEMORY.md 的边界 (Priority: P3)

**Goal**: USER.md 只读不写、MEMORY.md 写入路径唯一（经 save_memory → MemoryService → MarkdownMemoryStore）——边界核对兜底

**Independent Test**: grep 核对——无任何写 USER.md 的代码路径；MEMORY.md 写入仅经 MarkdownMemoryStore

- [x] T034 [US4] 边界核对（code review 类任务）：全仓 grep 确认 ① 无任何写 `USER.md` 的代码路径（`Files.write`/`writeString` 等落点不指向 USER.md）② `MEMORY.md` 写入仅经 `MarkdownMemoryStore`（save_memory 链路上游）③ **日志参数不含记忆内容/检索关键词**（NFR-003，S5-C1 修复——`LOG.*(content|keyword)` 落点核对，记忆内容与检索词不进日志参数）；核对结果记录到 flow-status.md 备注（002 口径）

**Checkpoint**: 边界核对通过；无代码改动

---

## Phase 7: Polish & Cross-Cutting Concerns

**目的**：全量回归 + 收尾证据

- [x] T035 全量回归 `mvn clean verify` 全绿（006 新增测试 + 001~005 全部回归 + 静态检查门禁 P3C/SpotBugs/FindSecBugs/PMD 等）——收尾 DoD 判定依据
- [x] T036 [P] 005 回归确认：`AxionToolContractTest` 参数化自动纳入两新 Tool（坑十二——name/description/inputSchema 非空）、`ToolRegistryTest` 全绿（不多不少）
- [x] T037 人工验证清单整理（quickstart.md「人工验证」6 项）：机器判卷部分（已由测试覆盖）vs 人工部分（Demo 二对话版真模型——无 key 如实记待办；跨进程目检；三档切换实机；后端故障实机）——输出到收尾报告，执行方法见 `references/manual-acceptance.md`

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: 无依赖，立即开始
- **Foundational (Phase 2)**: 依赖 Setup——**阻塞所有 user story**（三接口/枚举是全部实现的编译前提）
- **US1 (Phase 3)**: 依赖 Foundational——MVP，无其他 story 依赖
- **US2 (Phase 4)**: 依赖 US1（同文件 MarkdownMemoryStore.java 增量：截断/并发在 US1 基础读写之上）——不得与 US1 并行改同文件
- **US3 (Phase 5)**: 依赖 Foundational；与 US1 弱耦合（契约测试需三档齐后遍历——**T022 的参数化契约测试在三档实现完成后才绿**，harness 先行指测试先写好，最终全绿在 T029/T030 完成时）
- **US4 (Phase 6)**: 依赖 US1（MEMORY.md 写入路径在 US1 落地后才有核对对象）
- **Polish (Phase 7)**: 依赖全部 story

### User Story Dependencies

- **US1 (P1)**: Foundational 后即可开始，不依赖其他 story
- **US2 (P2)**: 在 US1 之后（同文件增量）
- **US3 (P2)**: Foundational 后即可开始，可与 US1 并行（不同文件；唯一交叉点是 US1 T016 与 US3 T032 都改 CliAgentConfiguration——顺序执行；T025 换档断言在 T032 后绿）
- **US4 (P3)**: US1 之后（核对对象就位）

### Within Each User Story

- 测试任务 MUST 先写且先红（编译不过 = 红），实现后转绿——不允许"最后补测试"（G3）
- 接口/实体先于服务；服务先于装配；装配先于全量回归
- 每个 task 完成即跑对应模块测试，红了当场修（不攒到最后）

### Parallel Opportunities

- Phase 1: T001∥T002
- Phase 2: T003∥T004∥T005（三个不同文件）
- US1 tests: T006∥T007∥T008（不同文件）；T009（core 模块）∥T010（cli 模块）
- US1 impl: T011∥T012∥T013（不同文件）；T014 在 T011 后（依赖 store 存在）；T015 在 T005 后；T016 依赖 T014/T015 产物
- US2: T018∥T019 先行；T020 与 T021 顺序（同文件）
- US3 tests: T022∥T023∥T024（不同文件）；T025 在 T010 之后（同文件增量）
- US3 impl: T026∥T027∥T028 并行，T029/T030 各自依赖对应实体/协议核实，T031∥T026-028
- **US1 与 US3 可并行**（唯一顺序约束：CliAgentConfiguration 相关任务 T016→T032→T025 转绿先后）

---

## Implementation Strategy

### MVP First（User Story 1 Only）

1. Phase 1 Setup → Phase 2 Foundational → Phase 3 US1（markdown 档 + 两 Tool + PromptBuilder 集成）
2. **STOP and VALIDATE**：`mvn test -pl axion-memory,axion-core,axion-cli -am` 绿 = 跨对话记偏好机器可判部分闭环
3. US1 即 Demo 二对话版的最小闭环（真模型人工验证留 Polish）

### Incremental Delivery

1. Foundational 完成 → 接口墙可见
2. + US1 → 跨对话记偏好（MVP）→ 可验
3. + US2 → 核心记忆底线钉死（截断/并发回归）→ 可验
4. + US3 → 三档换档 + 契约等价性 → 可验
5. + US4 → 边界核对 → 可验
6. Polish → 全量回归 + 人工项清单

---

## Notes

- [P] = 不同文件、无未完成依赖；[USn] 标签映射 spec user story
- 每条任务完成勾选 tasks.md 并跑模块测试，红了当场修
- **禁止自动 commit / push / 运行 package.sh**（axion-spec 纪律）——同步时机由用户决定
- **停止清单全程生效**：交付清单之外的对外概念 / 已定字面量改动 / 新第三方依赖 → 停下报告
- 宪法易违点预检（每 task 前）：原则 II（不碰 Spring AI 自动 tool 执行——两 Tool 纯 AxionTool 实现）、V（审计复用 ToolExecutor 路径）、VII（全程同步——FileChannel/synchronized/RestClient 同步阻塞）
- **日志隐私纪律（NFR-003，S5-C1 修复）**：SaveMemoryTool/RecallMemoryTool/MarkdownMemoryStore 等实现中的日志语句 MUST NOT 带记忆内容与检索关键词（用户隐私数据）；违规由 T034 grep 核对拦截
- **SC-004 上层零改动目检（S5-C3 修复）**：US3 完成后 git diff 目检 `PromptBuilder.java`/`SaveMemoryTool.java`/`RecallMemoryTool.java` 在 US3 阶段无改动（换档零代码改动的机械证据，收尾变更总结时核对）
- Mem0 协议 H3 核实（T030）：核实不到 → 停止清单第 5 条
- 测试方法名英文、中文语义用 `@DisplayName`（H5 纪律）
- T010/T025（cli 测试）为 2026-09-06 用户指示补强——补齐宪法质量门「每功能模块至少一个端到端测试」的 cli 模块缺口（005 先例留白）
