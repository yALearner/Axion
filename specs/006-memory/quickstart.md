# Quickstart: 006-memory 验证指南

> 运行前提（本机环境）：构建前 export JAVA_HOME 与 PATH（002 先例口径）。
> 自动化验证全绿 = 机器判卷部分完成；人工部分（Demo 二对话版等）见文末清单。

## 自动化验证（harness）

```bash
# 全量门禁（含 001~005 回归 + 静态检查门禁）——收尾 DoD 的判定依据
mvn clean verify

# 只跑本 feature 模块（日常迭代）
mvn test -pl axion-memory,axion-core,axion-cli -am
```

预期结果：axion-memory 新增 6 个测试类 + 002 PromptBuilderTest 改造后全绿 + 005 回归全绿。

| 测试类 | 验证点 |
|--------|--------|
| `MarkdownMemoryStoreTest` | **坑十五**写后立读；**坑十六**截断只裁归档核心一字不少；scope 路由（坑十七）；只搜归档（坑十八）；**并发追加 50 条零丢失**（自审 #1/#4 回归） |
| `LongTermMemoryStoreTest` | **参数化契约**：遍历三档实现钉死四条行为契约（等价性防线，自审 #5） |
| `SqliteMemoryStoreTest` | 手工 schema.sql 建表（坑八）；INSERT/CORE 全量+ARCHIVAL LIMIT/LIKE 语义与 markdown 档一致 |
| `Mem0MemoryStoreTest` | mock HTTP 层（MockWebServer）：add/get/search REST 翻译；非 2xx 异常上抛；凭证占位解析 |
| `MemoryToolsTest` | scope 缺省 archival、非法值报错（坑十七）；未命中返回"没有找到相关记忆"不抛异常；content 必填（005 S1 口径） |
| `MemoryServiceTest` | buildContext = 核心记忆 + 会话历史、归档不整体注入；remember/recall 正确委托 LongTermMemoryStore（接口墙） |

## 人工验证（机器判不了的部分；方法论见 `references/manual-acceptance.md`）

1. **Demo 二对话版（真模型）**：第一次对话说"我项目用 Spring Boot，部署在 K8s 上" → Agent 主动 save_memory；重启/新会话问"我的项目能用什么数据库" → 引用记忆作答（依赖真模型 key；无 key 如实记待办；Agent prompt 套用 axion-design template §六三件套）
2. **跨进程验证**：重启后 MEMORY.md 内容还在（目检）；sqlite 档切换后 memory_entries 表核对（scope 列正确）
3. **USER.md 只读核对（code review）**：grep 无写 USER.md 路径；MEMORY.md 写入仅经 MarkdownMemoryStore
4. **三档切换实机**：`memory.backend=sqlite` 启动 + 写入后查表；`memory.backend=mem0` 需自托管实例（本地无 → 如实记待办，mock 层已验协议翻译）
5. **后端故障实机**：mem0 档指向不可达地址 → 读写明确报错（不静默空记忆、不自动降级——自审 #2）
6. **落库核对**：save_memory/recall_memory 调用后 tool_invocations 落账正确（目检一眼）

## 本节不做（验证时不要误判为缺陷）

- 自动提炼/注入外泄扫描/语义检索自建层/情景记忆/Memory Wiki/记忆压缩（扩展阶段，信号驱动）
- 门面层缓存（坑十五契约的张力，扩展阶段）
- 手工编辑冲突检测（文件式特性，记录不处理）
- 多实例部署支持（单实例假设；跨进程 FileChannel 锁为纵深防御）
