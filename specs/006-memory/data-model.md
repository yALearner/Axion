# Data Model: 006-memory

> 记忆数据两形态：MEMORY.md 文件（markdown 档）与 memory_entries 表（sqlite 档）；会话记忆复用 sessions 表（002 已交付，本课零改动）。

## MEMORY.md 两区块（markdown 档，003 init 已建模板）

| 维度 | 口径 |
|------|------|
| 位置 | `.axion/memory/MEMORY.md`（003 init 模板：`# 长期记忆` + `## 核心记忆` + `## 归档记忆`） |
| 核心区 | `## 核心记忆` 下条目——**永不截断、每次 load 完整返回**（坑十六）；跨会话始终注入 system prompt |
| 归档区 | `## 归档记忆` 下条目——超 4000 字只裁尾部（坑十六：截断函数只接收归档段）；recallByKeyword 只在归档区搜（坑十八） |
| 条目格式 | `- [日期] 内容`（课件骨架口径；格式宽松，Agent 写什么 LLM 自己理解） |
| 并发与原子写 | append 双层互斥（synchronized + FileChannel.lock）+ 锁内重读 + 临时文件 ATOMIC_MOVE 替换；load 免锁（原子写保证读不到半写） |

## memory_entries 表（sqlite 档，schema.sql 手工增量，坑八口径）

| 列 | 类型 | 说明 |
|----|------|------|
| `id` | INTEGER PK AUTOINCREMENT | 自增主键 |
| `content` | TEXT NOT NULL | 记忆内容 |
| `scope` | TEXT NOT NULL | `CORE` / `ARCHIVAL`（分区语义与 MEMORY.md 两区块一一对应） |
| `created_at` | TEXT（ISO-8601） | 写入时间（复用 InstantTextConverter，003 口径） |

- **语义映射**：append→INSERT；load→`scope='CORE'` 全量 + `scope='ARCHIVAL'` 按 created_at 倒序 LIMIT（截断语义）；recall→`LIKE '%keyword%'` 仅 ARCHIVAL（坑十六/十八的 SQL 形态）
- **坑八口径**：测试执行生产同一份手工 schema.sql 建表，不依赖 ddl-auto 自动迁移

## 接口墙与枚举（core，依赖倒置端口）

| 类型 | 签名 | 说明 |
|------|------|------|
| `MemoryService` | `buildContext(Session)` / `remember(content, scope)` / `recall(keyword)` | 门面——上层零实现细节（技术方案 §5.1"墙"） |
| `LongTermMemoryStore` | `append(content, scope)` / `load()` / `recallByKeyword(keyword)` | 可插拔后端；四条行为契约全实现共用 |
| `MemoryScope` | `CORE` / `ARCHIVAL` | 坑十七：写哪区由 Agent 经 scope 显式声明，缺省 ARCHIVAL |

## 既有实体复用（零新增之外的边界）

- **sessions / SessionManager**（002/003）：会话记忆，本课零改动（门面委托）
- **tool_invocations**（002）：save/recall 审计复用——input_json 含记忆内容为已知事实（审计明文第二副本，留存语义 = 审计价值）
- **USER.md**（003 Bootstrap）：只读不写；无任何写 USER.md 的代码路径（code review 核对点）
