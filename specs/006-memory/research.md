# Research: 006-memory（技术选型与裁决记录）

> 本 feature 无未决 NEEDS CLARIFICATION——全部裁决已在需求文档拍板（2026-09-06：拍板 B + 设计期自审修复 #1~#9）。本文件记录裁决内容与备选，作为 plan/tasks 的依据。

## 裁决 1：后端档位——一次交付三档（拍板 B，2026-09-06）

- **Decision**: `MarkdownMemoryStore`（默认）+ `SqliteMemoryStore` + `Mem0MemoryStore` 一次交付，`memory.backend` 配置切换；整体以 `D:\项目\` 新版 PDF 课件为准
- **Rationale**: 用户拍板（2026-09-06）；新版课件 22 节与技术方案 §5.1 字面一致（旧 .md 课件"只做文件式"已过时）；接口墙从第一天完整可见
- **Alternatives considered**: 只做文件式（旧 .md 课件口径——否决：用户拍板 B + 新课件为三档口径）

## 裁决 2：MemoryService 接口落 core（依赖倒置）

- **Decision**: `MemoryService`/`LongTermMemoryStore`/`MemoryScope` 三个接口/枚举落 axion-core；实现/三档后端/两 Tool 落 axion-memory
- **Rationale**: PromptBuilder 在 core 调 MemoryService——若接口在 memory 则 core←memory 反向依赖（违反 CLAUDE.md 依赖方向）；001 `LlmGateway` 依赖倒置先例
- **Alternatives considered**: 接口落 memory + PromptBuilder 经 ContextLoader 间接触达（否决：绕路且门面失去意义）；PromptBuilder 移到 memory（否决：动 002 结构）

## 裁决 3：并发与原子写（自审 #1/#4，2026-09-06）

- **Decision**: append 双层互斥（进程内 synchronized + 跨进程 FileChannel.lock）+ **锁内重读**；写回临时文件（UUID 名）+ `Files.move(ATOMIC_MOVE, REPLACE_EXISTING)`；load 免锁（原子写保证读不到半写）；append 失败异常上抛
- **Rationale**: 单实例多 Agent 是核心阶段真实形态——跨会话虚拟线程并发 append 的读-改-写竞态会丢记忆；整体回写中途崩溃会损坏文件；ATOMIC_MOVE 同盘原子替换 + 锁内重读是零依赖的正确性解
- **Alternatives considered**: 仅 synchronized（否决：多实例共享文件系统无保护）；每 Agent 独立记忆文件（否决：改数据模型偏离"一份 MEMORY.md"口径）；数据库化 markdown 档（否决：丢人可读/git 可跟踪卖点）

## 裁决 4：后端故障行为（自审 #2）

- **Decision**: load/append/recall 遇后端故障 → 快速失败 + 明确报错；不静默空记忆、不自动降级换档
- **Rationale**: 记忆是 prompt 组装关键路径——静默空记忆让 Agent 带着错误认知作答比报错危险；自动降级掩盖选档配置错误误导运维
- **Alternatives considered**: 静默空记忆（否决：失忆不可见）；自动回退 markdown 档（否决：掩盖配置错误）

## 裁决 5：三档契约等价性（自审 #5）

- **Decision**: 新增 `LongTermMemoryStoreTest` 参数化契约测试——遍历三档实现钉死四条行为契约（不缓存/核心不截断/scope 路由/只搜归档）
- **Rationale**: 四条契约"全实现共用"是文档声明，三档独立测试会漏掉某一档的实现偏差（如 sqlite LIMIT 方向错）
- **Alternatives considered**: 三档各自独立测试（否决：等价性无统一锚点）

## 裁决 6：Mem0 档形态（FR-5 + 自审 #6/#8）

- **Decision**: RestClient 直连自托管 Mem0（无官方 SDK——零新依赖）；append/load/recall 翻译 add/get/search；凭证/地址 `${ENV_VAR}` 占位；自托管应 HTTPS；REST 协议实施时 H3 核实（核实不到 → 停止清单第 5 条）；每轮 REST 延迟诚实标注（缓存与坑十五张力记录，信号驱动）
- **Rationale**: 005 的 H3 教训（示意 API ≠ 现实 API）；数据不出域定位
- **Alternatives considered**: 引入 Mem0 官方 Java SDK（否决：无官方 SDK 存在 + 保持零新依赖）；本地起 Mem0 容器（否决：验证成本高，mock 层 + 真机待办更务实）

## 裁决 7：两 Tool 形态（005 机械适配延续）

- **Decision**: `SaveMemoryTool`/`RecallMemoryTool` 两顶层类 implements AxionTool（一个类只能实现一个 getName）；scope 参数 core/archival 缺省 archival、非法值明确报错；recall 未命中返回"没有找到相关记忆"不抛异常；content 必填校验（005 S1 口径）
- **Rationale**: G4-C1 全树扫描纪律 + 005 内置工具同款形态；坑十七（Agent 显式声明不猜）
- **Alternatives considered**: 单个 MemoryTools 类两 @Tool 方法（否决：AxionTool 单 getName 形态）；@Tool 注解直用（否决：005 拍板机械适配）

## 裁决 8：坑编号全局递增（坑十五~十八）

- **Decision**: 坑十五（不缓存）/坑十六（截断只裁归档）/坑十七（scope 显式）/坑十八（检索只搜归档）——对应课件节内"坑一~坑四"
- **Rationale**: 课件节内编号与全仓坑一~坑十四冲突，全仓唯一性优先
- **Alternatives considered**: 沿用课件节内编号（否决：与 001 坑一冲突）

## 裁决 9：审计表记忆明文副本（自审 #7）

- **Decision**: save_memory 的 content 落 tool_invocations.input_json——留存语义 = 审计价值，核心阶段不脱敏，文档诚实说明（004 口径延续）
- **Rationale**: 审计 day one 是宪法 V 硬约束；脱敏会损审计价值
- **Alternatives considered**: input_json 脱敏（否决：审计失真 + 超出核心阶段范围）

## 裁决 10：注入扫描与手工编辑冲突（自审 #3/#9）

- **Decision**: 记忆写入的注入/外泄安全扫描归扩展阶段（核心阶段信任 Agent 写入判断，诚实说明）；MEMORY.md 手工编辑与 Agent 写入无冲突检测（文件式特性，记录不处理）
- **Rationale**: 业界调研 §5.6 的 day-one 原则在手动写入口径下是既定风险；信号驱动升级原则
- **Alternatives considered**: 核心阶段上扫描（否决：无真实信号支撑，违反信号驱动原则）
