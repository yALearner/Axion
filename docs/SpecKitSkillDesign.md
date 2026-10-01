# Spec-Kit 流程编排 Skill 分析方案

> 目标：把 6 步 Spec-Kit 流程（specify → clarify → plan → tasks → analyze → implement）沉淀为一个可传参的 Skill，执行时附上需求文档编号即可跑完全流程，且全程遵守 Axion 宪法与项目原则。
>
> 状态：已定稿（§10 决策已拍板；2026-08-30 追加 §4.1 判断权 / §4.2 停止清单，commit 决策修订为全程不自动同步） | 关联文档：`docs/AiProgrammingGuide.md`、`.specify/memory/constitution.md`（v1.0.0）、`CLAUDE.md`

---

## 1. 背景与现状盘点

项目已有完备的 Spec-Kit 基础设施，但目前**没有统一入口**——流程靠人逐条敲命令，门禁靠人自觉：

| 已有资产 | 位置 | 状态 |
|---------|------|------|
| 6 个 speckit 命令（skills 形态） | `.claude/skills/speckit-*` | ✅ 已装，各自可独立调用 |
| 宪法（9 条原则 + 技术约束 + 开发工作流 + Governance） | `.specify/memory/constitution.md` | ✅ v1.0.0，已批准 |
| Spec-Kit 工作区 | `.specify/`（templates、memory、workflows、integrations） | ✅ 已初始化 |
| 首个 feature 产物 | `specs/001-react-runtime/`（spec/plan/tasks/data-model/contracts/checklists） | ✅ 质量基准 |
| 流程文档 | `docs/AiProgrammingGuide.md` §1~§4（含 §3.4 plan 人工 review 清单） | ✅ |
| 工程地基 skill | `.claude/skills/java-spring-init`（自带 `references/constitution-checklist.md`） | ✅ |
| 扩展点 | `.specify/extensions.yml`（before_* hooks） | ⬜ 未启用 |

**痛点**：① 6 步之间没有门禁串联，跳过 analyze 直接 implement 没有人拦；② 宪法约束靠每个会话自觉加载，换会话就丢；③ 流程中断后不知道跑到哪一步；④ 需求文档与 feature 产物之间没有编号约定。

**本方案要做的**：一个编排 skill（薄壳）+ 门禁细则 + 编号规范。**不做的**：不改写 speckit 命令本体、不修改宪法、不替代人工 review（宪法 Governance 明确 review 必须核对 9 条原则）。

---

## 2. 使用方式与编号解析

### 2.1 命令形态

```
/axion-spec <编号> [--from <stage>] [--dry-run]
```

- `编号`：需求文档编号（解析规则见 2.2）
- `--from <stage>`：断点续跑，从指定阶段继续（stage ∈ specify/clarify/plan/tasks/analyze/implement）
- `--dry-run`：只走预检 + 打印将执行的计划与注入的宪法检查点，不产出文件

### 2.2 编号解析规则（三层回退）

| 优先级 | 输入形态 | 解析 | 例子 |
|-------|---------|------|------|
| 1 | `NNN-slug` 且 `docs/requirements/NNN-slug.md` 存在 | 新需求文档 → 全程产物进 `specs/NNN-slug/` | `/axion-spec 002-memory` |
| 2 | `US-n`（n=1..5） | 映射到 `AiProgrammingGuide.md` §4.n 的 user story，需求源 = `DemandAnalysis.md` 对应能力章节 + 第 13 章验收 Demo；产物目录建议新 `specs/NNN-slug/`（需用户确认） | `/axion-spec US-3` |
| 3 | `NNN-slug` 且 `specs/NNN-slug/` 已存在但无需求文档 | 断点续跑模式（提示 `--from`） | `/axion-spec 001-react-runtime --from analyze` |

### 2.3 需求文档编号规范（提案，需确认）

项目目前没有 `docs/requirements/` 目录。提案：

```
docs/requirements/
└── NNN-slug.md        # NNN = 3 位递增序号（与 specs/NNN-slug 对齐）
                       # slug = 特性短名（如 memory-system、web-service）
```

- 需求文档模板沿用 spec-kit 的 spec 模板核心字段（User Scenarios / Requirements），由 `speckit-specify` 直接消费
- 若输入编号三种形态都匹配不上 → 明确报错并提示两种合法形态，**不静默猜测**

---

## 3. 流程编排：6 阶段 + 5 门禁

阶段顺序与宪法「开发工作流」一致：specify → clarify → plan → tasks → implement；`analyze` 插在 tasks 与 implement 之间作**硬门禁**（宪法"质量门"的落地）。

| 阶段 | 调用的 skill | 输入 | 输出 | 门禁（未过不得进下一阶段） |
|------|-------------|------|------|---------------------------|
| S0 预检 | —（编排器自执行） | 编号、宪法、`.specify` 状态 | 执行计划、TaskCreate 任务清单、进度文件初始化、**分支检查**、**feature.json 指针切换** | — |
| S1 specify | `speckit-specify` | 需求文档 + 宪法要点 | `specs/NNN/spec.md` | — |
| S2 clarify | `speckit-clarify` | spec.md | ≤5 个澄清问答，答案写回 spec | **G1**：spec 无未决问题（open question 清零） |
| S3 plan | `speckit-plan` | spec + 宪法 + 最新版 `TechnicalSolution.md` | `specs/NNN/plan.md` | **G2**：人工 review（清单见 3.1）通过 |
| S4 tasks | `speckit-tasks` | plan + spec | `specs/NNN/tasks.md` | **G3**：复核通过 + **固定软停点**（比对交付清单，停下等用户确认，不许跳过） |
| S5 analyze | `speckit-analyze` | spec + plan + tasks | 一致性分析报告 | **G4**：无 ERROR 级发现（WARNING 记录后放行） |
| S6 implement | `speckit-implement` | tasks.md | 代码 + 审计记录 | 每个 task 内自查（见 §4），全部 task 完成即流程完成 |

### 3.1 G2：plan 人工 review 清单（继承 `AiProgrammingGuide.md` §3.4，逐条核对）

- [ ] Memory 没有被简化成跟 Session 合并（应为 `MemoryService` 三层统一门面）
- [ ] Tool 没有被拆成多个模块（应为合并的 `axion-tool` 一个模块，宪法原则 IX）
- [ ] `AgentLoader` / `AGENT.md` 没有被当成 Tool（Agent 目录归 core 的 `ContextLoader`）
- [ ] 没有启用 Spring AI 自动 tool 执行（宪法原则 II，**最容易被写错的一条**）
- [ ] plan 的模块结构 = 技术方案第 10 章的 9 个模块（新增 Channel/Tool 只加新模块，不改 `axion-core`）
- [ ] 核心阶段不做清单被遵守（无认证/SSE/WebSocket/限流/RBAC 等扩展项混入）

### 3.2 G3：tasks 复核要点

- 按 user story 拆（宪法开发工作流），依赖顺序正确（US-1 → US-2 → US-3∥US-4 → US-5 或本 feature 自己的依赖 DAG）
- **审计表写入是显式任务**（宪法原则 V：`tool_invocations` + `llm_calls` day one 落库，不得以日志替代）
- 每个功能模块至少一个端到端测试用例（宪法质量门）
- acceptance criteria 来源 = 需求文档（主体阶段即 `DemandAnalysis.md` 第 13 章 Demo）

---

## 4. 执行总纪律（宪法注入 + 判断权 + 停止清单）

**单一真相源原则**：9 条原则、陷阱表、检查清单的**正文不复制进 skill**——SKILL.md 只引用路径，每阶段执行前现场读取最新版：

| 注入内容 | 源文件 | 注入时机 |
|---------|--------|---------|
| 宪法全文（9 原则 + 技术约束 + 工作流） | `.specify/memory/constitution.md` | S1~S6 每次调用前 |
| 常见陷阱表（tool 调两次、Provider 扫描、SQLite 迁移等） | `CLAUDE.md`「常见陷阱」 | S3 plan 与 S6 implement 前 |
| 宪法速查 checklist | `.claude/skills/java-spring-init/references/constitution-checklist.md`（复用，不另写） | S6 每个 task 开始前 |
| plan review 清单 | 本方案 §3.1 | G2 |
| 模块结构约束 | `CLAUDE.md` 模块结构 + 依赖方向 | S3、S4 |

### 4.1 判断权分配原则

- **能交给机器判断的，不留给人判断**：门禁（G1~G4）、宪法 9 条速查、测试全绿、字面量一致性核对等可机械验证的事项，全部由 skill 自动执行，不作为"人工 review 建议"推给用户
- **机器判断不了的，绝不自行发挥**：需求取舍、接口设计变更、验收标准理解分歧等需要判断的事项，触发 §4.2 停止清单后报告用户，等确认后继续

### 4.2 停止并报告清单（触发任一即停）

执行过程中遇到下列任一情况，**立即停止当前阶段，向用户报告具体情况，等待用户确认后继续；不得自行决定绕过**：

1. 需要创建本需求交付清单之外的任何对外概念（public 类型、类型、配置键、数据表、REST 路径、Profile 字段）
2. 需要修改任何已定字面量（类名、方法签名、配置键、表列名、端点路径）
3. 需求与 `TechnicalSolution.md` 冲突
4. 需要修改前序节交付的公共接口（当前需求明确列为"改造点"的除外）
5. 第三方 API 在本地依赖中核实不到
6. 需要新增 plan 未列明的第三方依赖
7. **反作弊红线**：不得删断言、不得加 `@Disabled`、不得放宽阈值让测试变绿——实现错就修实现；认为测试错，停下报告。**未全绿不得宣称完成**

### 4.3 S6 implement 的过程纪律（任务级门禁，2026-08-30 补齐短板时强化）

1. 每个 task 开始前：跑 9 条原则速查 + §4.2 停止清单预检，违反即停并报告，**不得静默绕过**
2. **写前（H3）**：涉及第三方 API 的任务先在本地依赖核实方法存在，核实不到 → 停止清单第 5 条
3. **写中（H1/H5）**：只创建交付清单点名的对外概念；已定字面量逐字保真；异常不吞；
   不建文档外抽象层；注释只写"为什么"；测试方法名英文（中文语义用 `@DisplayName` 保留）
4. **写后（任务级 DoD）**：实现与测试一起落地，红了当场修；**不自动 commit / push / 运行 package.sh**——同步时机由用户决定
5. 新建 Maven 模块时：调用 `java-spring-init` skill 搭地基（它自身已内置宪法核对）；**注意**其默认行为是"每步完成后 git commit"，与本纪律冲突——调用时 MUST 显式要求跳过 commit
6. 失败处理：报错如实上报（含输出），不跳过、不伪装成功；重试前先定位根因
7. 宪法冲突时：宪法为准（Governance：宪法 supersede 一切），冲突文档同步修正并报告

### 4.4 收尾 DoD（七项证据，2026-08-30 补齐短板时新增）

全流程结束前逐项出具证据，七项全过才可宣布完成（细则见 skill 的 `references/gates.md`）：
① `mvn clean verify` 全绿（含已接入的静态检查）② 端到端测试存在且非空、关键回归对号
③ 交付清单逐项存在性核对 ④ 前序 feature 全部测试回归绿 ⑤ 六条全局不变量自查
（涉外 IO 过 `Sandbox.enforce` / 审计双表成败都落 / 无明文 key / `session_id` 只在 `SessionManager` 拼接 /
无 Reactor·`CompletableFuture`·自建线程池 / 无 Spring AI 自动工具执行）
⑥ 剩余人工项清单 ⑦ 变更总结三段结构（改动点 / 重点 review 清单 / 如何验证）

**可选增强（P1，不在首版）**：启用 `.specify/extensions.yml` 的 `before_*` hooks 做宪法自动检查——spec-kit 各命令原生支持，首版靠编排器人工门禁跑顺后再考虑自动化。

---

## 5. 状态管理与断点恢复

- **进度文件**：`specs/NNN-slug/flow-status.md`，记录每阶段状态（`pending / in_progress / done / failed`）+ 每阶段产物哈希/时间戳 + 门禁结果 + feature.json 切换前指针
- **任务清单镜像**：编排器用 TaskCreate 建 6 个阶段任务，与进度文件互相印证
- **恢复**：`/axion-spec NNN-slug --from tasks` → 预检阶段读进度文件，校验前置产物存在且未变（哈希比对），从指定阶段续跑；产物被外部改动时警告并要求确认
- **幂等**：speckit 各命令本身可重跑；重跑阶段产物覆盖前先备份旧文件为 `.bak`

---

## 6. Skill 本体结构（渐进式披露，与项目哲学一致）

```
.claude/skills/axion-spec/
├── SKILL.md                        # 编排主逻辑：编号解析、阶段状态机、门禁触发、恢复
│                                   #   正文只放"怎么走流程"，不放细则
└── references/
    ├── gates.md                    # 5 个门禁的完整细则（G1~G4 + S6 过程纪律）
    ├── requirements-convention.md  # 需求文档目录/编号规范 + 文档模板
    └── injection-map.md            # 各阶段宪法注入内容映射表（引用源，不复制正文）
```

- SKILL.md 是唯一入口，frontmatter 声明 `user-invocable: true`，`argument-hint: "<需求编号> [--from <stage>]"`，description 说明三态编号
- 细则按需读取（渐进式披露）；宪法正文/陷阱表永远从源文件读，杜绝双份漂移

---

## 7. 与现有体系的边界

| 对象 | 关系 |
|------|------|
| `speckit-*` 6 个 skills | **纯复用**（Skill 工具调用），不复制其逻辑；它们升级本 skill 自动受益 |
| `java-spring-init` | S6 新建模块时调用；复用其 `constitution-checklist.md` 引用。其默认"每步 git commit"与 §4.3 不自动同步纪律冲突，调用时 MUST 要求跳过 commit |
| `.specify/memory/constitution.md` | **只读**。宪法修订流程走 Governance 三步（文档化→评审→迁移），skill 无权修改 |
| openspec-* skills（用户级） | 不混用。本项目统一走 Spec-Kit（宪法开发工作流已定） |
| 增量小改动（修 bug、加小 tool） | 仍按宪法与 guide §6 走手动提示词模式，**不强制**过本 skill；本 skill 面向"有需求文档编号"的特性开发 |

---

## 8. 风险与应对

| 风险 | 应对 |
|------|------|
| 门禁过严拖慢节奏（WARNING 也拦） | G4 只拦 ERROR；WARNING 记录放行，累积 3 个 WARNING 升级为 ERROR 提示人工 |
| clarify 中途被打断，答案丢失 | clarify 每答一题即写回 spec，而非全部答完一次性写 |
| 进度文件与 tasks.md 勾选状态漂移 | 进度文件只记阶段级状态；task 级进度唯一真相源 = `tasks.md` 的 `[x]` 勾选 |
| 需求文档缺失时 AI 自行脑补需求 | S0 解析失败即报错退出，提示先补需求文档（§2.3 模板） |
| 跨会话执行（会话中断） | 进度文件 + `--from` 恢复，换会话不影响 |
| skill 与 speckit 上游冲突 | skill 只做编排，不 fork 命令内容，冲突面最小 |

---

## 9. 验收标准

1. **全流程 dry-run**：`/axion-spec 002-xxx --dry-run` 打印出 6 阶段计划 + 每阶段注入的宪法检查点，无文件产出
2. **真跑**：拿一个新需求文档完整跑通，四件套（spec/plan/tasks/analyze 报告）质量对齐 `specs/001-react-runtime/`，且收尾 DoD 七项证据齐全（含 `mvn clean verify` 全绿与变更总结）
3. **门禁有效**：人为注入违反原则 IX（Tool 拆模块）的 spec → G2 必须拦截；人为制造 spec/tasks 不一致 → G4 必须拦截
4. **断点恢复**：在 tasks 阶段中断后，`--from tasks` 能无损续跑
5. **宪法零违反**：全流程 9 条原则抽查通过，审计表写入任务显式存在于 tasks.md
6. **停止清单有效**：注入场景（如 plan 未列明的新依赖、修改已定字面量、测试失败）时，skill 停下报告而非自行处理；测试全绿是宣称完成的必要条件（用注入失败测试验证反作弊红线）

---

## 10. 已拍板决策（2026-08-30 评审）

| # | 问题 | 决策 |
|---|------|------|
| 1 | 需求文档编号规范 | **`docs/requirements/NNN-slug.md`**（与 specs 目录对齐），US-n 作别名 |
| 2 | skill 名称 | **`axion-spec`** |
| 3 | 门禁严格度 | **ERROR 阻断、WARNING 记录放行**（累计 3 个 WARNING 升级为 ERROR 提示人工） |
| 4 | commit 粒度 | **全程不自动 commit / push / 运行 package.sh，同步时机由用户决定**（2026-08-30 修订，覆盖原"阶段+task 粒度"决策） |
