# Feature Specification: Memory 三层记忆（会话 + 长期）

**Feature Branch**: `006-memory`

**Created**: 2026-09-06

**Status**: Draft

**Input**: 需求文档 docs/requirements/006-memory.md（课件第 21/22 节：Memory 三层记忆；整体以 D:\项目\ 新版 PDF 课件为准）

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 跨对话记偏好（Priority: P1）

第一次对话告诉 Agent"我项目用 Spring Boot，部署在 K8s 上"，Agent 主动调 `save_memory` 写入长期记忆；重启 Axion 或新开会话；第二次对话问"我的项目能用什么数据库"，Agent 在响应里引用之前记的偏好给建议。这是 Agent OS 区别于 chatbot 的核心体验（Demo 二对话版，编程指南 §4.3）。

**Why this priority**: 本课存在的意义（课件 21 §一：Agent 从 Demo 到生产要跨的头号坎是记忆）；需求文档 §13 的 Memory 验收点与 Demo 二都锚定这条链。

**Independent Test**: 全 mock 单测验证 MemoryService 委托链与两 Tool；人工部分真模型走完整对话（无 key 如实记待办）。

**Acceptance Scenarios**:

1. **Given** 一次对话中 Agent 判断"这句话值得长期记住"，**When** 调 save_memory(content, scope)，**Then** 内容写入对应分区（scope 显式声明——坑十七），执行返回"已记住"，`tool_invocations` 落 success=true
2. **Given** 记忆已写入，**When** 下一次 prompt 组装（同进程甚至同一轮内），**Then** load 立即可见新记忆（坑十五：不缓存）
3. **Given** 重启进程后新开会话，**When** 问相关问题时 Agent 调 recall_memory，**Then** 命中相关记忆、拼回上下文作答
4. **Given** recall 关键词未命中，**When** 执行，**Then** 返回"没有找到相关记忆"（不抛异常）

---

### User Story 2 - 核心记忆始终在场（Priority: P2）

用户的关键约束（身份、项目背景、偏好）存在核心区，每次对话都完整注入 system prompt——不管归档区积累了多少、截断了多少，核心区一字不能少（坑十六）。

**Why this priority**: 核心记忆是 MemGPT core memory 思想在 Axion 的落地（课件 21 §十），解决"每次对话都记得你是谁"这个最影响体感的需求；实现成本接近零但底线最该钉死（截断逻辑将来任何"优化"都不能碰它）。

**Independent Test**: 最值钱回归测试：灌 500 条归档流水 → load 含核心条目、不含最早归档、含最近归档（坑十六）；并发追加 50 条零丢失。

**Acceptance Scenarios**:

1. **Given** 核心区有内容、归档区远超阈值，**When** load 执行，**Then** 核心区完整返回、归档区只保留最近部分（截断只裁归档）
2. **Given** 多个 Agent 会话并发调 save_memory，**When** 各自写入，**Then** 全部条目都在（双层互斥 + 原子写——零丢失、零半写损坏）

---

### User Story 3 - 记忆量增长的平滑升级（Priority: P2）

归档记忆从几十条涨到上千条、关键词检索开始找不准：把 `memory.backend` 从 markdown 换成 sqlite（结构化查询）或 mem0（语义检索），上层 PromptBuilder/MemoryTools 一行不改（接口墙价值兑现，信号驱动选档）。

**Why this priority**: "接口焊死、实现分阶段"是本课第一性原则（课件 21 §九）；一次交付三档（拍板 B）让接口墙从第一天就完整可见。

**Independent Test**: 三档实现各自的单测 + **参数化契约测试**（遍历三档钉死四条行为契约，行为等价性）；mem0 档 mock HTTP 层验证协议翻译。

**Acceptance Scenarios**:

1. **Given** 配置 `memory.backend=sqlite`，**When** 启动并读写记忆，**Then** 语义与 markdown 档一致（核心全量/归档 LIMIT/LIKE 检索）、数据落 memory_entries 表
2. **Given** 配置 `memory.backend=mem0`，**When** append/load/recall 执行，**Then** REST 翻译正确（add/get/search）、非 2xx 异常上抛不吞
3. **Given** 配置非法值，**When** 启动，**Then** 明确报错不静默（001 配置校验口径）
4. **Given** 后端运行中不可用（服务挂/文件不可读），**When** 记忆读写执行，**Then** 快速失败明确报错，不静默返回空记忆、不自动降级换档

---

### User Story 4 - USER.md 与 MEMORY.md 的边界（Priority: P3）

`USER.md` 是用户手写的初始设定、底座只读不写；`MEMORY.md` 是 Agent 的成长记录、底座读写。两者都进 system prompt，来源和生命周期不同（技术方案 §5.4）。

**Why this priority**: 边界混淆会导致"用户手写配置被 Agent 改掉"的信任事故；code review 级的核对足以兜底，优先级低于功能链。

**Independent Test**: grep 核对——无任何写 USER.md 的代码路径；MEMORY.md 写入仅经 MarkdownMemoryStore（save_memory 链路上游）。

**Acceptance Scenarios**:

1. **Given** 全仓代码，**When** code review 核对，**Then** USER.md 零写路径、MEMORY.md 写入路径唯一（经 save_memory → MemoryService → MarkdownMemoryStore）

---

### Edge Cases

- **并发写竞态**：多 Agent/多会话虚拟线程同时 save_memory → 双层互斥（进程内 synchronized + 跨进程 FileChannel.lock）+ 锁内重读，读-改-写全程互斥（FR-003）
- **半写损坏**：写回中途崩溃 → 临时文件 + ATOMIC_MOVE 原子替换，任何时刻磁盘上要么旧文件要么新文件（FR-003）
- **后端不可用**：load/append/recall 快速失败明确报错——不静默空记忆（静默失忆比报错危险）、不自动降级（掩盖选档配置错误）（FR-006）
- **归档超阈值**：4000 字截断只裁归档区、核心区一字不少（坑十六）
- **scope 非法值**：SaveMemoryTool 明确报错（坑十七：core/archival 之外的输入不猜）
- **关键词未命中**：返回"没有找到相关记忆"不抛异常
- **手工编辑冲突**：MEMORY.md 人可读/git 可跟踪的副作用——无冲突检测，文件式特性记录不处理
- **注入内容写入记忆**：核心阶段信任 Agent 写入判断，注入/外泄扫描归扩展阶段（明确不做，诚实说明）
- **load 不缓存**：写后立读（坑十五）；缓存与坑十五的根本张力记录（扩展阶段上门面缓存必须挂钩 save_memory 失效）
- **多实例共享文件系统**：跨进程 FileChannel 锁纵深防御；核心阶段单实例假设

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: 系统 MUST 提供 `MemoryService` 统一门面（接口落 core、依赖倒置——001 LlmGateway 先例）：buildContext(Session)（核心记忆 + 会话历史，供 PromptBuilder）/ remember(content, scope) / recall(keyword)；内部会话记忆委托 SessionManager、长期记忆委托 LongTermMemoryStore，上层零实现细节
- **FR-002**: 系统 MUST 提供 `LongTermMemoryStore` 可插拔接口：append(content, scope) / load() / recallByKeyword(keyword)；四条行为契约全实现共用——①不缓存（坑十五）②核心区永不截断、截断只裁归档（坑十六）③scope 显式指定系统不猜（坑十七）④关键词检索只在归档区、不做复杂化（坑十八）
- **FR-003**: 系统 MUST 提供 `MarkdownMemoryStore`（默认档）：MEMORY.md 两 header 分区；**并发与原子写约定**——append 双层互斥（进程内 synchronized + 跨进程 FileChannel.lock）且**锁内重读**、写回用临时文件（UUID 名）+ ATOMIC_MOVE 原子替换、load 免锁（原子写保证读不到半写）、append 失败异常上抛由 ToolExecutor 审计
- **FR-004**: 系统 MUST 提供 `SqliteMemoryStore`：memory_entries 表（手工 schema.sql 增量，坑八口径）；append→INSERT、load→CORE 全量 + ARCHIVAL 时间倒序 LIMIT、recall→LIKE；与 markdown 档语义一致（契约测试钉死）
- **FR-005**: 系统 MUST 提供 `Mem0MemoryStore`：自托管 Mem0 REST 集成（RestClient 直连，凭证/地址 `${ENV_VAR}` 占位；自托管应 HTTPS）；append/load/recall 翻译 add/get/search；REST 协议实施时 H3 核实（核实不到 → 停止清单第 5 条）；非 2xx 异常上抛不吞
- **FR-006**: 系统 MUST 提供 `axion.memory.backend` 配置键（markdown 缺省 / sqlite / mem0）：非法值启动校验明确报错；装配处显式 @Bean 按值装配（宪法 III 哲学）；**后端故障快速失败明确报错**（不静默空记忆、不自动降级）
- **FR-007**: 系统 MUST 提供 `SaveMemoryTool`/`RecallMemoryTool`（implements AxionTool 纯实现，005 机械适配）：save_memory——content 必填、scope 可选（core/archival 缺省 archival、非法值明确报错）、成功返回"已记住"；recall_memory——keyword 必填、未命中返回"没有找到相关记忆"不抛异常
- **FR-008**: 系统 MUST 完成 PromptBuilder 集成（002 改造点）：构造器新增 MemoryService 参数；组装 system prompt 时 buildContext(session) 拼入（核心记忆 + 会话历史，归档经 load 截断后注入）；每次重新读（坑十五联动）

### Non-Functional Requirements

- **NFR-001**: 全程同步阻塞，不引入异步模型；并发由 Java 21 虚拟线程承担；Mem0 REST 同步调用（宪法 VII）
- **NFR-002**: 审计 day one：save_memory/recall_memory 成败都落 tool_invocations——复用 ToolExecutor 既有路径（005 已注册进 ToolRegistry），本课零新增审计逻辑（宪法 V）
- **NFR-003**: 结构化 JSON 日志沿用；**记忆内容与检索关键词不进日志参数**（用户隐私数据；审计表留痕属设计行为——记忆明文第二副本为已知事实）

### Key Entities *(include if feature involves data)*

- **MemoryService / LongTermMemoryStore**: 接口墙——上层（PromptBuilder/MemoryTools/ReActLoop）只认这两个抽象
- **MemoryScope**: CORE / ARCHIVAL——写哪区由 Agent 显式声明（坑十七）
- **MEMORY.md（两区块）**: `## 核心记忆`（永不截断、始终注入）+ `## 归档记忆`（可截断、按需检索）
- **memory_entries 表**: sqlite 档的行存储——id/content/scope/created_at
- **审计记录（tool_invocations）**: 复用既有表与路径（含记忆内容副本的已知事实）

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 跨对话记偏好闭环——Demo 二对话版：第一次对话 save_memory 写入 → 重启/新会话 → Agent 引用记忆作答（真模型人工；无 key 如实记待办）
- **SC-002**: 核心记忆 100% 完整——灌 500 条归档流水后 load 仍含核心区一字不少（坑十六回归）
- **SC-003**: 并发追加零丢失——50 虚拟线程各写一条，load 全部命中（双层互斥 + 原子写回归）
- **SC-004**: 换档零代码改动——memory.backend 三种取值下 006 全部测试绿、上层（PromptBuilder/MemoryTools）无任何改动
- **SC-005**: `mvn clean verify` 全绿（006 测试 + 001~005 全部回归），无任何跳过/放宽

## Assumptions

- **前序交付物已就位、无缺口**：AxionTool/ToolResult/JsonSchema/LlmGateway 先例（001）、PromptBuilder/SessionManager/Session/ToolExecutor（002）、InitCommand 已建 MEMORY.md 模板（003）、ToolRegistry/RestClient 装配/坑八模式（005）——现状实测确认（2026-09-06）
- **单实例假设**：核心阶段单实例部署；跨进程 FileChannel 锁为纵深防御（多实例共享文件系统的已知边界）
- **三档一次交付（拍板 B）**：MarkdownMemoryStore 默认 + SqliteMemoryStore + Mem0MemoryStore + memory.backend 切换；整体以 D:\项目\ 新版 PDF 课件为准
- **Mem0 协议 H3 核实**：实施时核实自托管版 REST 形态，核实不到停下报告；本地无实例 → mock HTTP 层单测 + 真机验证待办
- **自动提炼/注入扫描/语义检索**归扩展阶段：核心阶段信任 Agent 写入判断（信号驱动口径，文档诚实说明）
- **改造点**：PromptBuilder 构造器加 MemoryService 参数（技术方案 §5.3 明文集成点）+ CliAgentConfiguration 装配更新——其余前序公共接口零改动
- **记忆数据隐私**：内容不进日志参数；审计表 input_json 含记忆内容为已知事实（留存语义 = 审计价值，核心阶段不脱敏）
