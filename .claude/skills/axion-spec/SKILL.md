---
name: oryx-spec
description: >-
  按 OryxOS 规范流程开发一个需求：传入需求文档编号（docs/requirements/NNN-slug.md 或 US-n），
  依次执行 speckit-specify → clarify → plan → tasks → analyze → implement 六阶段（含门禁与断点恢复），
  全程遵守 .specify/memory/constitution.md 宪法、CLAUDE.md 陷阱表与本 skill 执行纪律
  （判断权分配、停止并报告清单、不自动 commit / push / package.sh）。
  当用户说「跑需求 / 按 Spec-Kit 流程开发 / oryx-spec」时使用。
argument-hint: "<需求编号> [--from <stage>] [--dry-run]"
user-invocable: true
disable-model-invocation: false
---

# oryx-spec：需求开发全流程编排

OryxOS 项目需求开发的统一入口。设计依据：`docs/SpecKitSkillDesign.md`。
本文件只描述流程骨架；细则按需读取（渐进式披露）：
门禁与纪律 → `references/gates.md`；编号规范 → `references/requirements-convention.md`；
宪法注入映射 → `references/injection-map.md`。

## 0. 参数解析

- **编号**：按 `references/requirements-convention.md` 的三层回退解析。
  - 命中「需求文档」→ 全程产物进 `specs/NNN-slug/`
  - 命中「US-n」→ 需求源 = `docs/AiProgrammingGuide.md` §4.n + `DemandAnalysis.md` 对应能力章节
    + 第 13 章验收 Demo；产物目录**建议** `specs/NNN-slug/`（NNN = specs/ 下下一个可用序号，
    slug 由该 US 能力名推导），**停下请用户确认目录**后再继续
  - 命中「已存在 feature 目录」→ 断点续跑模式，提示用户补 `--from`
  - **三层都不命中 → 报错退出**，列出合法形态，绝不猜测
- `--from <stage>`：从指定阶段续跑（specify / clarify / plan / tasks / analyze / implement）。
  续跑前校验：① 前置产物存在且与 `flow-status.md` 记录的哈希一致 ② feature.json 指针指向目标目录；
  任一不一致 → 停下报告
- `--dry-run`：只执行 S0 预检并打印完整执行计划（六阶段 + 每阶段注入点 + 门禁），
  不产出文件、不调用子 skill

## 1. S0 预检（每次执行必做）

1. 读取 `references/injection-map.md`、`.specify/memory/constitution.md` 全文、`CLAUDE.md`「常见陷阱」表
2. 读取 `references/gates.md`（门禁细则、停止清单、过程纪律、收尾 DoD）
3. 检查 `.specify/` 与目标 `specs/NNN-slug/` 目录状态；`flow-status.md` 存在则读取
4. **feature.json 指针切换**（speckit 子命令靠它定位产物目录）：
   - 当前指向已是 `specs/NNN-slug/` → 通过
   - 指向其他目录：若该目录的 `flow-status.md` 显示 `in_progress` → 可能另有 feature 在跑，
     **停下报告**，不自动覆盖；否则更新为 `{"feature_directory": "specs/NNN-slug/"}`，
     旧指针记入 flow-status.md
5. **分支检查**：确认不在 main/主干上开发。speckit-specify 的 before_specify hook 会建分支；
   hook 未配置或分支不存在 → 停下报告，请用户确认分支策略
   （手动 `git checkout -b {NNN}-{slug}` 或复用既有分支），不得在主干直接开发
6. 用 TaskCreate 建立六阶段任务清单（S1~S6）
7. 初始化/校验 `specs/NNN-slug/flow-status.md`（模板见 `references/gates.md`）
8. `--dry-run`：在此处打印计划后退出，**不修改任何文件（含 feature.json、flow-status.md）**

## 2. 六阶段执行（每阶段完成后更新 flow-status.md）

### S1 specify
调用 `speckit-specify`，$ARGUMENTS 含：需求文档路径 + 宪法要点（见 injection-map S1 行）。
产物：`spec.md`。

### S2 clarify
调用 `speckit-clarify`。**每回答一题立即把答案写回 spec.md**，再问下一题。
门禁 G1：spec 无未决 open question。

### S3 plan
调用 `speckit-plan`，$ARGUMENTS 含：spec 路径 + 宪法全文路径 + `docs/TechnicalSolution.md`
（必须是最新版）+ 模块结构约束（9 模块）。
门禁 G2：按 gates.md §G2 六条清单逐条核对，用 AskUserQuestion 请用户逐条确认；
任一条不过 → 停下报告，不进入 S4。

### S4 tasks
调用 `speckit-tasks`。
门禁 G3：按 gates.md §G3 复核（user story 拆解与依赖顺序、审计表写入是显式任务、
端到端测试覆盖、acceptance criteria 来源、harness 先行）。

**固定软停点（流程中唯一，不许跳过）**：自动比对 tasks.md ↔ 需求文档「交付清单」
（代码/测试/配置/表逐项），输出比对结果（齐 / 缺什么 / 多什么），
**停下等用户确认**后才进入 S5。

### S5 analyze
调用 `speckit-analyze`。
门禁 G4：ERROR 阻断；WARNING 记录放行，累计 3 个升级为 ERROR 并提示人工。

### S6 implement
调用 `speckit-implement`。过程纪律见 gates.md §过程纪律（写前 H3 / 写中 H1·H5 / 写后任务级 DoD），要点：
- 每个 task 开始前：宪法 9 条速查（java-spring-init 的 constitution-checklist.md）
  + 停止清单预检（gates.md §停止清单）
- 新建 Maven 模块时调用 `java-spring-init`，并 MUST 在 $ARGUMENTS 中要求「跳过 git commit」
- **不自动 commit / push / 运行 package.sh**——同步时机由用户决定
- 全部 task 完成且测试全绿 → 更新 flow-status.md，执行 gates.md §收尾 DoD 后按 §4 格式报告完成

## 3. 总纪律（全程生效，细则见 gates.md）

- **判断权分配**：能机器判断的不留给人；机器判断不了的不自行发挥
- **停止并报告清单 7 条**：触发任一 → 立即停止当前阶段、向用户报告、等确认后继续，不得自行绕过
- **反作弊红线**：不删断言、不加 `@Disabled`、不放宽阈值；测试未全绿不得宣称完成

## 4. 完成报告格式（收尾 DoD）

流程结束按 gates.md §收尾 DoD 逐项出具证据（七项全过才可宣布完成），报告结构：
六阶段结果表（阶段 / 产物 / 门禁结论）、DoD 七项证据、停止清单触发记录（如有）、
**变更总结三段结构**（改动点 / 重点 review 清单 / 如何验证，直接输出在对话里，不另开文件）、
未 commit 的变更清单（提示同步时机由用户决定）。
