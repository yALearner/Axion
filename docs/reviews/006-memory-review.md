# 006-memory 代码 Review 指南

> 生成：2026-09-06（交付后复盘 + 人工验收闭环后）；对应 PR #7。
> 复盘全记录见 `specs/006-memory/flow-status.md`（含 S0 续跑补录、G2/G4 门禁处置、SpotBugs 2 项修复、T036 跨模块断言落位），本指南是 review 导航。

## 一、全景：记忆写入 → 三档存储 → prompt 注入

```
Agent 主动 save_memory(content, scope)（坑十七：写哪区由 Agent 显式声明，缺省 ARCHIVAL）
  → SaveMemoryTool（AxionTool 纯实现）→ MemoryService.remember（门面，core 接口）
  → LongTermMemoryStore.append（按 axion.memory.backend 三档装配）：
      markdown（默认）→ MEMORY.md 两 header 分区，双层互斥 + 锁内重读 + ATOMIC_MOVE 原子写
      sqlite           → memory_entries 表 INSERT（schema.sql 手工增量，坑八）
      mem0             → POST /memories（自托管 OSS 无 /v1/ 前缀，H3 核实；scope 落 metadata）
  → ToolExecutor 落 tool_invocations 审计（成败都落，本课零新增审计逻辑）

每轮 prompt 组装（PromptBuilder）：
  buildContext(session) = LongTermMemoryStore.load()（核心全量 + 归档截断后，坑十六）
                          + Session 会话历史渲染
  → 拼入 system prompt（bootstrap 之后、日期时间之前——日期仍在 system 最末）
  → 每次重新读不缓存（坑十五：save_memory 下一轮立刻可见）

Agent 主动 recall_memory(keyword) → MemoryService.recall → 后端只搜归档区（坑十八）
  → 未命中返回「没有找到相关记忆」不抛异常（模型可换词重试，Demo 二实拍「数据库」→「用户偏好」）
```

四条行为契约（坑十五~十八）由 `LongTermMemoryStoreTest` 参数化遍历三档钉死——行为等价性防线（自审 #5）。

## 二、逐文件梳理

### axion-core/com/axion/core（依赖倒置端口 3 件 + 1 改造）

| 文件 | 职责与关键点 |
|------|-------------|
| `MemoryService` | 统一门面接口（buildContext/remember/recall）——LlmGateway 同款依赖倒置：PromptBuilder 在 core 调它，core←memory 反向依赖不成立 |
| `LongTermMemoryStore` | 可插拔后端接口；javadoc 钉死四条行为契约 + FR-6 快速失败（不静默空记忆、不自动降级） |
| `MemoryScope` | CORE/ARCHIVAL 枚举——坑十七的显式声明载体 |
| `PromptBuilder`（002 交付物改造） | 构造器 +MemoryService 参数；system 组装插 buildContext 输出（技术方案 §4.2 第 2 部分）；日期时间行保持 system 最末 |

### axion-memory/com/axion/memory（实现 + 三档 + 两 Tool，7 个文件）

| 文件 | 关键点 |
|------|--------|
| `MemoryServiceImpl` | 门面实现：buildContext = load() + 会话历史渲染（传入 session 是权威内存态——当前轮消息尚未落库，不回查 SQLite）；EI_EXPOSE_REP2 抑制（004 先例） |
| `MarkdownMemoryStore` | **并发与原子写**（FR-3）：append `synchronized` + 伴生 `.lock` 文件 FileChannel.lock（记忆文件被原子替换、锁不能挂它上面）+ 锁内重读 + 临时文件 ATOMIC_MOVE；load 免锁（原子写保证读不到半写）；截断只接收归档段（4000 字保留最近——核心区物理上动不到）；文件缺失自愈模板；读失败 IllegalStateException 上抛 |
| `SqliteMemoryStore` | JdbcTemplate（spring-jdbc 显式声明）：INSERT / CORE 全量 + ARCHIVAL `ORDER BY id DESC LIMIT 200` 再翻转（与 markdown「最新在尾部」口径一致）/ LIKE 仅归档 + 通配符转义 `ESCAPE '\'`；库损坏 DataAccessException 自然上抛 |
| `Mem0MemoryStore` | RestClient 直连自托管（X-API-Key 头）：POST /memories（user_id 固定租户 axion + messages 原文 + metadata.scope）/ GET /memories?user_id= / POST /search（query+filters.user_id+top_k=20）；scope 缺失按 ARCHIVAL；归档 4000 字客户端截断；非 2xx 上抛不吞 |
| `SaveMemoryTool` / `RecallMemoryTool` | AxionTool 纯实现（005 机械适配）：content/keyword 必填、scope 缺省 archival、非法 scope 走 ToolResult.failure（重试救不了参数错误、不标 retryable）；未命中友好措辞不抛异常；写入失败异常上抛由 ToolExecutor 审计 |

### axion-storage（模式机械延伸 + 建表）

| 文件 | 关键点 |
|------|--------|
| `MemoryEntry` / `MemoryEntryRepository` | 实体无 setter、InstantTextConverter 复用；Web Service 节管理端点直接消费本表口径 |
| `schema.sql` | 增量追加 memory_entries（id/content/scope/created_at）；坑八——测试与生产同一份手工脚本 |

### axion-cli / axion-boot（装配与配置）

| 文件 | 关键点 |
|------|--------|
| `CliAgentConfiguration` | 换档装配：`environment.getProperty("axion.memory.backend","markdown")` switch 三档——sqlite 走 ObjectProvider 可选注入（markdown 档不要求数据源在场）；mem0 缺凭证启动报错；非法值启动报错（001 口径）；两 Tool 注册进 ToolRegistry；MemoryService 注入 PromptBuilder |
| `application.yaml` | `axion.memory.backend: markdown` + mem0 凭证占位注释；验证时可用 `AXION_MEMORY_BACKEND` 环境变量会话级覆盖（宽松绑定），零 yaml 改动 |

## 三、重点 review 清单（按风险排序）

1. **MarkdownMemoryStore 并发正确性**（`MarkdownMemoryStore.java:69-85,134-147`）：双层互斥 + 锁内重读 + ATOMIC_MOVE 是 load 免锁的前提；锁文件必须是伴生 `.lock`（锁挂在被替换的文件上会失效）——这是丢记忆风险的唯一防线，回归钉 = 50 虚拟线程并发测试
2. **截断语义**（`truncateIfNeeded`）：只保留最近 4000 字；需求文档「裁尾部」措辞与「保留最近」回归测试（含 499 不含 0）冲突时以测试为准
3. **PromptBuilder 注入位置**（`PromptBuilder.java:76-88`）：buildContext 在 bootstrap 之后、日期之前；「当前日期时间」必须仍在 system 最末（002 契约，测试断言钉死）
4. **Mem0 协议翻译**（`Mem0MemoryStore.java`）：H3 核实结论（自托管 OSS 无 /v1/ 前缀、POST /search）与需求文档「add/get/search」示意不同，已按核实实现；真机验证待办
5. **换档装配**（`CliAgentConfiguration.java:118-150`）：非法值/mem0 缺凭证启动明确报错；后端故障快速失败不降级（降级会掩盖选档配置错误）
6. **SqliteMemoryStore LIMIT 200**：需求文档未钉条数，实现级取值已 javadoc 说明（markdown 4000 字截断语义的 SQL 形态）；LIKE 通配符转义防模式注入
7. **宪法 V 显式审计断言**（`MemoryToolsTest.java` 两用例）：save/recall 经 ToolExecutor 成败都落 tool_invocations——本课零新增审计逻辑，复用 005 路径的机器证据

## 四、刻意留白（review 时不要当成缺陷报）

1. **自动提炼/自动抽取**：核心阶段不做——写入靠 Agent 主动 save_memory（信号驱动；切 mem0 档时其自带抽取是外部能力，技术方案 §5.5 口径）
2. **门面层缓存**：不做——与坑十五「不缓存」契约存在根本张力，真成瓶颈再上门面缓存且失效必须挂钩 save_memory
3. **手工编辑冲突检测**：不做——MEMORY.md 人可读/git 可跟踪的副作用，文件式特性记录不处理
4. **记忆写入的注入/外泄安全扫描**：归扩展阶段——核心阶段信任 Agent 写入判断（业界调研 §5.6 的 day-one 原则在手动写入口径下是既定风险，诚实说明）
5. **审计表记忆明文副本**：input_json 含记忆内容，留存语义 = 审计价值，核心阶段不脱敏（004 口径）
6. **多实例共享文件系统**：单实例假设——跨进程 FileChannel 锁是纵深防御，多实例一致性归扩展阶段
7. **MEMORY.md 文件缺失自愈**：store 侧按 003 同款模板重建（init 已保证模板存在，此处兜底）
8. **cli 模块测试**：005 先例留白，本课经用户指示补强（CliAgentConfigurationTest 装配断言 + 换档断言）
9. **mem0 每轮延迟**：每轮 ReAct 迭代一次 REST（~100ms-1s），核心阶段诚实标注成本

## 五、建议 review 顺序

1. `MemoryScope` → `LongTermMemoryStore` → `MemoryService`（先看懂契约墙与四条行为契约）
2. `MarkdownMemoryStore`（并发/原子写/截断——本课最需要细看的一段）+ `MarkdownMemoryStoreTest`（回归钉）
3. `SqliteMemoryStore` + `Mem0MemoryStore`（两档语义映射）+ `LongTermMemoryStoreTest`（参数化等价性）
4. `SaveMemoryTool`/`RecallMemoryTool` + `MemoryToolsTest`（审计断言）
5. `PromptBuilder`（002 改造点）+ `CliAgentConfiguration`（换档装配）+ `CliAgentConfigurationTest`

## 六、当前验收状态

- **人工验收全部闭环**（2026-09-06）：
  - Demo 二对话版真模型跑通（双 profile 双 session 隔离实证：save_memory 主动调用 → MEMORY.md 落条目 → 全新会话模型连调 2 次 recall_memory（「数据库」未命中→「用户偏好」命中）→ 答复开篇引用「基于我的长期记忆」并推荐 PostgreSQL）
  - 审计落库查库核对（save×1 + recall×2 全 success=1、session 关联正确、durationMs 有值）
  - sqlite 档实机（env 覆盖换档：装配启动正常 + schema 自动建表 + 真模型写入查表 scope=ARCHIVAL/ISO-8601 + MEMORY.md 未被写入的换档隔离）
  - 故障三测实机：非法 backend 启动报错；mem0 缺凭证启动报错；mem0 不可达 chat 快速失败（请求路径无 /v1/ 前缀再实证 H3）
- `mvn clean verify` 全绿：342 tests（006 新增 43）+ 全静态门禁；SpotBugs 2 项按 004 先例修复（CRLF 日志净化 + EI_EXPOSE_REP2 抑制）
- **剩余待办（如实记录，不阻塞合并）**：① 真实自托管 Mem0 实例验证（本地无实例，mock 层已验协议翻译，需业务方实例时补跑）② 004 遗留「接口中立性自查」用户思维练习
