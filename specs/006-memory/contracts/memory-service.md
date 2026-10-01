# 接口契约：MemoryService（记忆统一门面）

> **跨节契约**：本节交付后，第 25 节（定时触发后的记忆读写）、第 31 节（Demo 二/三日报的记忆偏好）为消费方；Web Service 节如做记忆管理端点直接消费本契约与 memory_entries 表口径。**后续节不得改动已验收行为**；修改本契约视为修改公共接口，必须停下报告。

## 接口形态（依赖倒置端口，落 axion-core）

```java
MemoryService.buildContext(Session session)          // 给 PromptBuilder：核心记忆 + 会话历史
MemoryService.remember(String content, MemoryScope scope)   // 给 SaveMemoryTool
MemoryService.recall(String keyword)                 // 给 RecallMemoryTool

LongTermMemoryStore.append(String content, MemoryScope scope)
LongTermMemoryStore.load()                           // 核心区全量 + 归档区截断后
LongTermMemoryStore.recallByKeyword(String keyword)

MemoryScope = CORE | ARCHIVAL
```

## 行为不变量

1. **接口墙**：上层（PromptBuilder/MemoryTools/ReActLoop）只认 MemoryService——不直接碰 SessionManager 或 MEMORY.md（技术方案 §5.1）
2. **四条行为契约（全后端实现共用）**：
   - 坑十五：**不缓存**——每次重新读（save_memory 下一轮立刻可见）
   - 坑十六：**核心记忆永不截断**——截断只作用归档区（核心区一字不少）
   - 坑十七：**scope 显式**——写哪区由 Agent 声明，系统不猜（缺省 ARCHIVAL）
   - 坑十八：**检索只在归档区**——关键词匹配，不做复杂化
3. **后端故障快速失败**（自审 #2）：load/append/recall 后端不可用 → 明确报错；不静默空记忆（失忆不可见比报错危险）、不自动降级换档（掩盖选档配置错误）
4. **并发与原子写**（自审 #1/#4，markdown 档）：append 双层互斥 + 锁内重读 + ATOMIC_MOVE；load 免锁
5. **审计 day one**（宪法 V）：save/recall 成败复用 ToolExecutor 落 tool_invocations——本课零新增审计逻辑
6. **USER.md 只读**：无任何写 USER.md 的代码路径；MEMORY.md 写入仅经 MarkdownMemoryStore

## 三档后端与配置

| 后端 | memory.backend 值 | 形态 |
|------|------------------|------|
| MarkdownMemoryStore（默认） | `markdown` | MEMORY.md 两区块；4000 字归档截断；contains 行匹配 |
| SqliteMemoryStore | `sqlite` | memory_entries 表；LIMIT 截断；LIKE 检索 |
| Mem0MemoryStore | `mem0` | 自托管 REST（add/get/search）；语义检索；数据不出域 |

- 配置键 `axion.memory.backend`：非法值启动校验明确报错；装配处显式 @Bean 按值装配（宪法 III 哲学）
- 换档 = 一行配置，上层零改动（接口墙价值兑现）

## 演进

- 扩展阶段：门面后加 in-memory cache（**失效必须挂钩 save_memory**——坑十五契约的张力，信号驱动再上）；注入/外泄扫描；语义检索自建层或外部集成
- 三档实现行为差异由 `LongTermMemoryStoreTest` 参数化契约测试钉死（等价性防线）
