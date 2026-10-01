# oryx-spec 门禁细则与执行纪律

## 门禁

### G1（S2 clarify 之后）
- 检查 `spec.md` 中不得遗留未决 open question / TODO / "待定"
- clarify 的每道题答案必须已写回 spec 对应位置

### G2（S3 plan 之后，人工 review，必须逐条请用户确认）
六条清单（继承 `docs/AiProgrammingGuide.md` §3.4）：

1. Memory 没有被简化成跟 Session 合并（应为 `MemoryService` 三层统一门面）
2. Tool 没有被拆成多个模块（应为合并的 `oryxos-tool` 一个模块，宪法原则 IX）
3. `AgentLoader` / `AGENT.md` 没有被当成 Tool（Agent 目录归 core 的 `ContextLoader`）
4. 没有启用 Spring AI 自动 tool 执行（宪法原则 II，**最容易被写错的一条**）
5. plan 的模块结构 = 技术方案第 10 章的 9 个模块（新增 Channel/Tool 只加新模块，不改 `oryxos-core`）
6. 核心阶段不做清单被遵守（无认证/SSE/WebSocket/限流/RBAC 等扩展项混入）

操作方式：用 AskUserQuestion 一次列出六条，每条选项「通过 / 不过（附原因）」；
任一条不过 → 停下报告，等用户给出修正方向。

### G3（S4 tasks 之后）
- 按 user story 拆解，依赖顺序正确（宪法开发工作流）
- **审计表写入是显式任务**（宪法原则 V：`tool_invocations` + `llm_calls` day one 落库，不得以日志替代）
- 每个功能模块至少一个端到端测试用例（宪法质量门）
- acceptance criteria 来源 = 需求文档（主体阶段即 `DemandAnalysis.md` 第 13 章 Demo）
- **harness 先行**：测试任务必须先于或伴随对应实现任务出现，不允许"最后补测试"

### 固定软停点（S4 之后，流程中唯一，不许跳过）

自动比对 `tasks.md` ↔ 需求文档「交付清单」：代码/测试/配置/表逐项核对，
测试任务是否 harness 先行；输出比对结果（齐 / 缺什么 / 多什么），
**停下用 AskUserQuestion 等用户确认**后才进入 S5。

### G4（S5 analyze 之后）
- **ERROR 阻断**：有 ERROR 级发现 → 停下报告，不进 S6
- **WARNING 放行**：记录在 flow-status.md 后放行；累计 3 个 WARNING 升级为 ERROR 并提示人工

---

## 判断权分配原则

- **能交给机器判断的，不留给人判断**：门禁（G1~G4）、宪法 9 条速查、测试全绿、
  字面量一致性核对等可机械验证的事项，全部由 skill 自动执行，不作为"人工 review 建议"推给用户
- **机器判断不了的，绝不自行发挥**：需求取舍、接口设计变更、验收标准理解分歧等
  需要判断的事项，触发停止清单后报告用户，等确认后继续

---

## 停止并报告清单（触发任一即停）

执行过程中遇到下列任一情况，**立即停止当前阶段，向用户报告具体情况，等待用户确认后继续；
不得自行决定绕过**：

1. 需要创建本需求交付清单之外的任何对外概念（public 类型、类型、配置键、数据表、
   REST 路径、Profile 字段）
2. 需要修改任何已定字面量（类名、方法签名、配置键、表列名、端点路径）
3. 需求与 `TechnicalSolution.md` 冲突
4. 需要修改前序节交付的公共接口（当前需求明确列为"改造点"的除外）
5. 第三方 API 在本地依赖中核实不到
6. 需要新增 plan 未列明的第三方依赖
7. **反作弊红线**：不得删断言、不得加 `@Disabled`、不得放宽阈值让测试变绿——
   实现错就修实现；认为测试错，停下报告。**未全绿不得宣称完成**

报告格式：触发的条目编号 + 具体情况（涉及的文件/符号/差异）+ 建议选项，用 AskUserQuestion 等确认。

---

## 过程纪律（S6 implement，任务级门禁）

**每个 task 开始前：**
- 宪法 9 条速查（`../java-spring-init/references/constitution-checklist.md`）+ 停止清单预检；
  违反即停并报告，**不得静默绕过**

**写前（H3）：**
- 涉及第三方 API 的任务，先在本项目依赖里核实方法存在（`mvn dependency:tree` /
  本地仓库 jar 反查）；核实不到 → 停止清单第 5 条，停下报告

**写中（H1/H5）：**
- 只创建「交付清单」点名的对外概念（停止清单第 1 条）
- 已定字面量逐字保真（停止清单第 2 条）
- 异常不吞：catch 必落审计/日志或上抛，不得空 catch
- 不建需求文档之外的抽象层；注释只写"为什么"
- **测试方法名必须是英文**（驼峰或 snake_case），中文语义用 `@DisplayName` 保留

**写后（任务级 DoD）：**
- 实现与测试一起落地，跑该模块测试，红了当场修，不攒到最后
- 任务完成即更新 tasks.md 勾选；**不自动 commit / push / 运行 package.sh**——同步时机由用户决定

**其他：**
- 新建 Maven 模块时：调用 `java-spring-init` 搭地基，$ARGUMENTS 中 MUST 要求「跳过 git commit」
  （其默认行为是每步 commit，与本纪律冲突）
- 失败处理：报错如实上报（含输出），不跳过、不伪装成功；重试前先定位根因
- 宪法冲突时：宪法为准（Governance：宪法 supersede 一切），冲突文档同步修正并报告

---

## 收尾 DoD（七项证据，全部满足才可宣布完成）

1. `mvn clean verify` 全绿（若项目已接入静态检查门禁（P3C/SpotBugs/FindSecBugs/PMD 等），
   必须一并全绿），贴关键输出
2. 端到端测试存在且非空，关键回归点逐个对号
3. 需求文档「交付清单」逐项 ls/grep 存在性核对
4. **前序 feature 全部测试回归绿**（跨 feature 契约证据）
5. **六条全局不变量逐条自查**：
   ① 涉外 IO（文件/Shell/HTTP）必须过 `Sandbox.enforce`
   （若当前 feature 阶段 Sandbox 未就位：留调用位并在 tasks 注明接线任务）
   ② LLM 调用成败都落 `llm_calls`、工具执行成败都落 `tool_invocations`
   ③ grep 无明文 key ④ `session_id` 只在 `SessionManager` 内拼接
   ⑤ 无 Reactor / `CompletableFuture` / 自建线程池 ⑥ 无 Spring AI 自动工具执行路径
6. 验收报告：以上证据 + 需求文档验收标准中**剩余人工项清单**，明确告知用户
   「机器已判卷的部分与等你人工过的部分」（人工项执行方法见 `references/manual-acceptance.md`）
7. **变更总结三段结构**（直接输出在对话里，不另开文件，以 `git status --short` /
   `git diff --stat` 实测为准）：
   - **改动点**：按模块分组列新增/移动/修改/删除的文件，每处一句话说明动机；
     前序 feature 文件被本节触碰的（哪怕只改 import）单独标出
   - **重点 review 清单**：按风险排序 3~6 条——架构决策（依赖方向、契约变化）优先，
     其次宪法易违点（原则 II/IV/V/VII/IX），每条给文件行级定位
   - **如何验证**：可直接复制执行的命令块（全量门禁、只跑本 feature 测试、关键回归单测、
     依赖方向 grep 等），每条注明预期结果；最后重复剩余人工项

---

## flow-status.md 模板

```markdown
# Flow Status: <NNN-slug>

需求文档: docs/requirements/<NNN-slug>.md（或 US-n 来源说明）
feature.json 指针: specs/<NNN-slug>/（切换前: <旧值或无>）
创建时间: <ISO 时间>

| 阶段 | 状态 | 产物 | 哈希 | 门禁结论 | 备注 |
|------|------|------|------|---------|------|
| S1 specify | pending | spec.md | — | — | |
| S2 clarify | pending | spec.md（更新） | — | G1: — | |
| S3 plan    | pending | plan.md | — | G2: — | |
| S4 tasks   | pending | tasks.md | — | G3: — | |
| S5 analyze | pending | 分析报告 | — | G4: — | |
| S6 implement | pending | 代码 | — | 测试: — | |

WARNING 记录: （累计 N/3）

停止清单触发记录:
- （时间）条目 N：具体情况 → 用户决策
```

状态取值：`pending / in_progress / done / failed`；哈希 = 阶段完成后产物的 sha256（S6 记测试结果）。
