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

1. [x] **Demo 二对话版（真模型，2026-09-06 已闭环）**：双 profile 双 session 隔离实证——写链 save_memory 主动调用落 MEMORY.md；读链全新会话连调 2 次 recall_memory（「数据库」未命中→「用户偏好」命中，坑十八未命中友好措辞间接实证）→ 答复引用「基于我的长期记忆」并推荐 PostgreSQL（Agent prompt 套用 axion-design template §六三件套）
2. [x] **跨进程验证（2026-09-06 已闭环）**：两次对话为两个独立 JVM 进程，重启后 MEMORY.md 内容仍在（文件式天然跨重启）
3. [x] **USER.md 只读核对（code review，已闭环）**：grep 无写 USER.md 路径（唯一写点为 003 InitCommand 幂等模板）；MEMORY.md 写入仅经 MarkdownMemoryStore
4. [x] **sqlite 档切换实机（2026-09-06 已闭环）**：环境变量 `AXION_MEMORY_BACKEND=sqlite` 换档（零 yaml 改动）→ schema 自动建表 → 真模型写入 → 查表 scope=ARCHIVAL/ISO-8601 → MEMORY.md 未被写入（换档隔离实证）
5. [x] **后端故障实机（2026-09-06 已闭环）**：非法 backend 值启动报错（「非法值: bogus（取值 markdown/sqlite/mem0）」）；mem0 缺凭证启动报错；mem0 指向不可达地址 → chat 快速失败「Mem0 读取失败」不静默不降级（请求路径无 /v1/ 前缀再实证 H3）
6. [x] **落库核对（2026-09-06 已闭环）**：save×1 + recall×2 全落 tool_invocations、success=1、session 关联正确、durationMs 有值
7. [ ] **mem0 档真实自托管实例验证（待办，跑法见下节）**：本地无实例 → 如实记待办，mock 层已验协议翻译

## mem0 真机验证跑法（待办 7 的详细步骤，2026-09-06 沉淀）

> 前置：任一台能跑 Docker 的机器（实例不要求与 Axion 同机）；两个 LLM key——Axion 自己的
> DeepSeek key + mem0 提炼用的 key（可用 DeepSeek 兼给，见下）。

### 第 1 步：起自托管 Mem0 实例（Docker）

```powershell
# 方式 A：官方镜像（最简）
docker pull mem0/mem0-api-server
docker run -d --name mem0 -p 8000:8000 `
  -e OPENAI_API_KEY="<mem0 提炼用的 LLM key>" `
  -e JWT_SECRET="<openssl rand -base64 48 生成>" `
  -e AUTH_DISABLED=true `
  mem0/mem0-api-server

# 方式 B：官方仓库 docker compose（API 在 8888，带配套组件）
git clone https://github.com/mem0ai/mem0 && cd mem0/server && make bootstrap
```

两个事实（H3 口径，细节以拉到的 mem0 server 版本文档为准）：
1. **mem0 自带事实提炼需要一个 LLM**（默认 OpenAI）。无 OpenAI key 时配 OpenAI 兼容端点指向
   DeepSeek（base_url），具体环境变量名以 `mem0/server/.env.example` 为准。
2. `AUTH_DISABLED=true` 是开发模式（免 JWT）；但 `Mem0MemoryStore` 强制 `MEM0_API_KEY` 非空，
   此时随便填一个占位值即可（dev 模式下 X-API-Key 头被忽略）。

### 第 2 步：Axion 侧换档 + 写入

```powershell
$env:AXION_MEMORY_BACKEND="mem0"
$env:MEM0_BASE_URL="http://localhost:8000"   # 或 8888
$env:MEM0_API_KEY="dev-mode-占位值"
$env:DEEPSEEK_API_KEY="<Axion 自己的 key>"
java -jar axion-boot\target\axion-boot-0.1.0-SNAPSHOT.jar chat --profile memo-writer --message "我项目用 Spring Boot，部署在 K8s 上，帮我记住"
```

### 第 3 步：mem0 侧核对（真机翻译正确性）

```powershell
# Swagger：http://localhost:8000/docs（免认证直连）；或 curl：
curl http://localhost:8000/memories?user_id=axion
```

核对三点：① 有记忆落库（POST /memories 翻译正确）② metadata 含 `{"scope": "ARCHIVAL"}`
（分区语义落地）③ 无 /v1/ 前缀的路径被接受（H3 结论真机确认）。

### 第 4 步：语义检索验证（真机最有价值的一步，mock 验不到）

```powershell
java -jar axion-boot\target\axion-boot-0.1.0-SNAPSHOT.jar chat --profile memo-reader --message "我的项目部署环境是什么？"
# 预期：recall_memory("部署环境") 经 POST /search 语义命中 "K8s"——关键词档检索不到同义表述，
# 语义档能命中，这就是切 mem0 档的理由
```

### 第 5 步：预期差异口径 + 回写

- **真机与 mock 的最大差异**：mem0 事实提炼会**改写原文**（写入"我项目用 Spring Boot"，存回可能是
  提炼后的表述）——已拍板口径（技术方案 §5.5：抽取是 mem0 外部能力），**不要当缺陷报**。
- 结果回写 `flow-status.md`「人工验收待办」最后一条并勾掉。

## 本节不做（验证时不要误判为缺陷）

- 自动提炼/注入外泄扫描/语义检索自建层/情景记忆/Memory Wiki/记忆压缩（扩展阶段，信号驱动）
- 门面层缓存（坑十五契约的张力，扩展阶段）
- 手工编辑冲突检测（文件式特性，记录不处理）
- 多实例部署支持（单实例假设；跨进程 FileChannel 锁为纵深防御）
