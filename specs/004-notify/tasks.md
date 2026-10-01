# Tasks: Notify 出站推送模块

**Input**: Design documents from `specs/004-notify/`（plan.md / spec.md / data-model.md / contracts/notify-channel.md / research.md / quickstart.md）

**Prerequisites**: plan.md ✓、spec.md ✓（4 个 user story）、data-model.md ✓、contracts/ ✓（notify-channel）、research.md ✓（裁决 1~8）

**Tests**: 已明确要求（需求文档验收标准 harness 分层两批：WebhookNotifyAdapterTest / NotifyToolsTest / NotifyChannelRegistryTest / NotifyChannelRepositoryTest；全部单测不碰外网，MockWebServer 属单测层）

**Organization**: 按 user story 分组，每个 story 独立可测、可独立交付。

## 格式约定

- `[P]`：可并行（不同文件、无依赖）
- `[USx]`：归属 user story；Setup/Foundational/Polish 不标
- 所有文件路径为仓库根目录相对路径，包名 `com.axion.*`
- **审计口径**（宪法 V）：notify 成败都进 `tool_invocations`——复用 `ToolExecutor` 既有路径（本 feature 不新增审计逻辑、不改 ToolExecutor），由 T011 实现 + T010 断言 + T015 核对共同钉死

---

## Phase 1: Setup（共享基础设施）

**Purpose**: 依赖核实 + pom 落地

- [X] T001 依赖核实（写前 H3 门禁）后改 `axion-tool/pom.xml`——增加 `axion-storage`（compile，Registry 消费 Repository 所需）、`spring-web`（compile，RestClient）、`spring-boot-starter-test`（test，JUnit+Mockito 栈，axion-core 同款先例）、`com.squareup.okhttp3:mockwebserver`（test，全仓首次引入）。写前核实：① `RestClient` 类在 spring-web 内（本地仓库 jar 反查）② mockwebserver 版本由 Boot BOM 管理（`mvn dependency:tree` 确认无显式版本号可解析）；核实不到 → 停止清单第 5 条停下报告

---

## Phase 2: Foundational（阻塞性前置）

**Purpose**: 通知渠道全局注册表地基（`notify_channels` 表 + 实体 + 仓储 + 坑八 harness），完成前不得开始任何 story

- [X] T002 [P] 在 `axion-storage/src/main/resources/schema.sql` **增量追加** `notify_channels` DDL（`name` TEXT PK、`type` TEXT NOT NULL、`url` TEXT NOT NULL、`description` TEXT 可空；`CREATE TABLE IF NOT EXISTS`，列口径见 data-model.md——坑八：不依赖 `ddl-auto=update`）
- [X] T003 [P] 创建 `NotifyChannelEntity` 在 `axion-storage/src/main/java/com/axion/storage/NotifyChannelEntity.java`——JPA 实体，`name` 主键；无 ISO 时间列故不涉 `InstantTextConverter`（storage flat 包沿用，字段见 data-model.md）
- [X] T004 [P] 创建 `NotifyChannelRepository` 在 `axion-storage/src/main/java/com/axion/storage/NotifyChannelRepository.java`——`JpaRepository<NotifyChannelEntity, String>`（按 name 主键查）
- [X] T005 [P] **harness 先行**：编写 `NotifyChannelRepositoryTest` 在 `axion-storage/src/test/java/com/axion/storage/NotifyChannelRepositoryTest.java`——**坑八口径**（003 同款 JDBC 先例）：测试执行手工 `src/main/resources/schema.sql` 建临时库（不让 Hibernate 自动建）；`PRAGMA table_info` 核对 4 列；可存可读；**name 主键唯一约束生效**（重复插入报错）；**description 可空**（先写、先跑、确认失败）

**Checkpoint**: 注册表地基就绪——四个 story 可开始

---

## Phase 3: User Story 1 - 配置好的群里收到 Agent 推的消息（Priority: P1）★ MVP

**Goal**: notify 工具自身链路——注册表解析 → 显式 Map 选 adapter → `enforce(HTTP_REQUEST)` 先于 send → 假 webhook 收到 POST（FR-1~3/5/6 + NFR-3 审计带渠道名）

**Independent Test**: `mvn test -pl axion-tool,axion-storage -am` 全绿（NotifyToolsTest 全链路 mock + WebhookNotifyAdapterTest 假 webhook + RegistryTest）；人工真 webhook 归 quickstart 人工项

### Tests for US1（harness 先行，先写先跑确认失败）

- [X] T006 [P] [US1] 编写 `NotifyChannelRegistryTest` 在 `axion-tool/src/test/java/com/axion/tool/notify/NotifyChannelRegistryTest.java`——mock `NotifyChannelRepository`：按名命中 → config 含 url+name；未命中 → 明确异常；**缺省三态**：唯一渠道取它 / 多条报错 / 空报错（拍板口径）
- [X] T007 [P] [US1] 编写 `WebhookNotifyAdapterTest` 在 `axion-tool/src/test/java/com/axion/tool/notify/WebhookNotifyAdapterTest.java`——MockWebServer 起假 webhook：断言收到的 POST body 带 `content`、content-type JSON；**URL 来自 `NotifyTarget.config` 而不是硬编码**（换 config 换目标）；**坑十一回归：webhook 返回 5xx 时异常上抛、不静默吞掉**
- [X] T008 [P] [US1] 编写 `NotifyToolsTest` 在 `axion-tool/src/test/java/com/axion/tool/builtin/NotifyToolsTest.java`——mock `Sandbox`/adapter Map/`Registry`：成功 → `ToolResult.success("已推送到 <渠道名>")`（**审计带渠道名**）；渠道未配置 → 明确报错；缺省唯一渠道可取；**adapter Map 无对应 channelType → 明确报错**；**坑十回归：InOrder 断言 `enforce` 先于 `send`**（需求文档 §四原文式样）

### Implementation for US1

- [X] T009 [US1] 创建 `NotifyChannelAdapter` 接口 + `NotifyTarget` record 在 `axion-tool/src/main/java/com/axion/tool/notify/`——接口唯一方法 `send(NotifyTarget target, String content)`，**签名零渠道实现词**；record 两字段 `channelType` + `config: Map<String,String>`（FR-1/FR-2；包内注释说明 config 键约定 url/name）
- [X] T010 [US1] 实现 `NotifyChannelRegistry` 在 `axion-tool/src/main/java/com/axion/tool/notify/NotifyChannelRegistry.java`——构造注入 `NotifyChannelRepository`；`resolve(channel)`：显式名未命中 → 明确异常；缺省口径按拍板（恰好一条取它、多条或空报错）；解析出 `NotifyTarget(channelType=type, config={url, name})`；**纯数据——adapter 选择不在本类**（FR-5）
- [X] T011 [US1] 实现 `WebhookNotifyAdapter` 在 `axion-tool/src/main/java/com/axion/tool/notify/WebhookNotifyAdapter.java`——构造注入 `RestClient`（课件签名逐字）；`send`：URL 只从 `target.config().get("url")` 取不硬编码；POST + content-type JSON + body 通用 text 格式 `{"msgtype":"text","text":{"content": content}}`（2026-09-05 人工验收 40008 实锤修正）；**异常原样上抛，不 catch 不吞**（坑十一；FR-3）。**不加 `@Component` 等组件注解，纯类交付**（G4-C1 钉死：boot 扫描 com.axion 全树，误加会因缺 RestClient bean 启动失败；第 20 节装配处显式 `@Bean` 构建后才可被扫描——裁决 7）
- [X] T012 [US1] 实现 `NotifyTools` 在 `axion-tool/src/main/java/com/axion/tool/builtin/NotifyTools.java`——implements `AxionTool`：`getName()="notify"`；schema 两参数 `content` 必填 `channel` 可选；构造注入 `Sandbox` + `Map<String, NotifyChannelAdapter>` + `NotifyChannelRegistry`；`execute` 四步钉死：① registry.resolve(channel) ② 按 `target.channelType()` 从 Map 选 adapter、未知 type 明确报错 ③ `sandbox.enforce(new SandboxAction(ActionType.HTTP_REQUEST, url))` **先于 send** ④ `adapter.send(target, content)`；成功返回 `ToolResult.success("已推送到 " + name)`、失败异常上抛复用 ToolExecutor 审计路径（FR-6；**审计不新增逻辑**）。**不加 `@Component` 等组件注解，纯类交付**（G4-C1 钉死：Sandbox/Map/Registry bean 均未就位，误加启动即崩；第 20 节装配处显式 `@Bean`——003 FR-10 口径）

**Checkpoint**: US1 独立可测——notify 自身链路走通（MVP）

---

## Phase 4: User Story 2 - 换渠道不碰 Agent（Priority: P2）

**Goal**: 全局注册表按名引用口径的数据层证据——换 URL 只改表一行，Agent 引用零改动；frontmatter 不含 notify_channels 字段

**Independent Test**: T013 回归 + T005 存读全绿 + T014 grep 核对通过

### Tests for US2

- [X] T013 [P] [US2] **harness 先行**：`NotifyChannelRegistryTest` 增补换渠道回归（`axion-tool/src/test/java/com/axion/tool/notify/NotifyChannelRegistryTest.java`）——注册两条渠道按名各自命中不串；**同一 name 换 URL 后解析取新 URL**（换渠道不碰 Agent 的数据层证据）
- [X] T014 [US2] frontmatter 口径核对（机器可判核对任务，无需代码）：grep 验证 ① axion-core 的 `Profile`/`AgentLoader` 派生逻辑无 `notify_channels` 键 ② 本 feature 未新增任何 Profile 字段/config 键（交付清单"无新增配置键"）；结果记录到 tasks.md 本任务勾选说明

---

## Phase 5: User Story 3 - 出站与入站同一道墙（Priority: P2）

**Goal**: 白名单先行钉死——enforce 拒绝时零外发请求；违规异常不吞

**Independent Test**: T015 违规路径回归全绿

### Tests for US3

- [X] T015 [US3] **harness 先行**：`NotifyToolsTest` 增补违规路径（`axion-tool/src/test/java/com/axion/tool/builtin/NotifyToolsTest.java`）——mock `sandbox.enforce` 抛 `SandboxViolationException` → 断言 **`send` 不得被调用**（零外发）+ 异常明确上抛（不吞；US3 验收场景 2"域名不在白名单 → 明确报错且不发生任何外发请求"）

**Checkpoint**: 白名单先行钉死（坑十 + 违规路径双回归）

---

## Phase 6: User Story 4 - 定时日报 Agent 的出口（Priority: P3）

**Goal**: 跨节契约就位——交付物与契约逐项核对，后续 20/25/31 节消费零改动

**Independent Test**: T016 逐项 grep 核对通过（交付物清单 100% 存在 + 契约不变量 1~8 无违反）

### Implementation for US4

- [X] T016 [US4] 交付物清单与契约核对（机器可判核对任务，无需代码）：① 交付清单逐项 grep 存在性——5 类（NotifyChannelAdapter/NotifyTarget/WebhookNotifyAdapter/NotifyChannelRegistry/NotifyTools）+ 1 实体 1 仓储 + pom 4 依赖 + schema.sql 增量 ② 按 `specs/004-notify/contracts/notify-channel.md` 不变量 1~8 逐条比对代码（签名零渠道词、URL 无硬编码、enforce 先于 send、成功带渠道名、无装配类无注册接线、Sandbox 接口注入）③ **审计落账路径未被绕过**——本 feature 无任何绕过 ToolExecutor 的审计路径（宪法 V）④ 确认无对 `axion-core` 的任何改动；结果记录到本任务勾选说明

**Checkpoint**: 跨节契约核对完成

---

## Phase 7: Polish & Cross-Cutting Concerns

**Purpose**: 全量门禁 + 收尾 DoD

- [X] T017 全仓 `mvn clean verify` 全绿——含静态门禁（P3C/SpotBugs/FindSecBugs/PMD 等）与 001/002/003 全部回归；红了当场修（不攒、不删断言、不加 @Disabled）
- [X] T018 按 `specs/004-notify/quickstart.md` 执行自动化部分核对 + 输出人工项清单（真 webhook、DB 落库核对、反例验证、接口中立性自查、errcode 陷阱留意——执行方法见 `references/manual-acceptance.md`）
- [X] T019 收尾 DoD 七项证据 + 变更总结三段结构（gates.md §收尾 DoD；变更总结直接输出对话，以 `git status --short` / `git diff --stat` 实测为准）

---

## Dependencies & Execution Order

```text
Phase 1   T001（pom）
            │
Phase 2   T002 T003 T004 ── T005（测试先行）
            │
Phase 3   T006 T007 T008 ── T009 T010 T011 T012
            │（T010 依赖 T003/T004；T012 依赖 T009/T010/T011）
Phase 4   T013 T014（依赖 Phase 3 的 Registry/全链）
Phase 5   T015（依赖 T012）
Phase 6   T016（依赖 Phase 3~5 全绿）
Phase 7   T017 T018 T019（依赖全部）
```

## 并行执行示例

- **Phase 2**：T002 / T003 / T004 / T005 并行（不同文件；T005 测试先行独立跑）
- **Phase 3 测试三件**：T006 / T007 / T008 并行（不同测试文件）
- **Phase 3 实现**：T010 与 T011 并行（Registry 与 WebhookAdapter 互不依赖）；T009 先行（T011 依赖接口）
- **Phase 4~5**：T013 / T014 / T015 并行（不同文件）

## Implementation Strategy

- **MVP = US1**（+ Foundational）：T001~T012 完成即"notify 自身链路走通"，可立即人工真 webhook 验证
- **增量交付**：US1 → US2（换渠道数据层证据）→ US3（白名单先行钉死）→ US4（契约核对）→ Polish
- **测试纪律**：harness 先行（T005/T006/T007/T008/T013/T015 先写先跑确认失败再实现）；跑法 `mvn test -pl axion-tool,axion-storage -am` 日常全跑，收尾 `mvn clean verify`
- **边界纪律**：无新增交付清单之外的对外概念；无装配类（RestClient bean 与 adapter 显式 Map 归第 20 节装配处）；不改 axion-core；不自动 commit/push/package.sh
