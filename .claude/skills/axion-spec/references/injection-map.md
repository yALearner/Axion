# 宪法注入映射表

> **单一真相源原则**：以下所有内容一律「引用路径、现场读取」，**禁止把正文复制进任何 skill 文件**，
> 防止宪法/陷阱表修订后 skill 内副本漂移。

| 阶段 | 注入内容 | 源文件 | 注入方式 |
|------|---------|--------|---------|
| S0 预检 | 宪法全文、陷阱表、本表 | `.specify/memory/constitution.md`、`CLAUDE.md`「常见陷阱」 | 现场 Read |
| S1 specify | 宪法要点（与需求相关的原则条目摘引路径）+ 需求文档 | 同上 + `docs/requirements/NNN-slug.md` | 拼入 `speckit-specify` 的 $ARGUMENTS |
| S2 clarify | spec + 宪法（澄清答案不得与原则冲突） | `.specify/memory/constitution.md` | 拼入 `speckit-clarify` 的 $ARGUMENTS |
| S3 plan | spec + 宪法全文 + **最新版** `TechnicalSolution.md` + 9 模块结构与依赖方向 + 陷阱表 | 上述源 + `CLAUDE.md` 模块结构 | 拼入 `speckit-plan` 的 $ARGUMENTS |
| S4 tasks | plan + 宪法质量门（审计 day one、端到端测试、US 依赖顺序） | `.specify/memory/constitution.md` 开发工作流/技术约束 | 拼入 `speckit-tasks` 的 $ARGUMENTS |
| S5 analyze | spec + plan + tasks 三产物一致性 + 宪法 9 条 | 三产物 + 宪法 Core Principles | 拼入 `speckit-analyze` 的 $ARGUMENTS |
| S6 implement（每 task 前） | 宪法 9 条速查 + 停止清单 + 陷阱表 | `.claude/skills/java-spring-init/references/constitution-checklist.md`（复用）+ `references/gates.md` §停止清单 + `CLAUDE.md` 陷阱表 | 现场 Read，逐 task 自查 |

## 各阶段 $ARGUMENTS 拼装示例

```
S1: 「需求文档：docs/requirements/002-memory-system.md。
    宪法要点参考：.specify/memory/constitution.md（原则 III/V/VII，技术约束-持久化）。
    产物目录：specs/002-memory-system/」

S3: 「spec：specs/002-memory-system/spec.md。
    宪法：.specify/memory/constitution.md（全文必读）。
    技术方案：docs/TechnicalSolution.md（第 10 章 9 模块结构为准，模块数必须一致）。
    陷阱：CLAUDE.md 常见陷阱表（Spring AI 双调用、Provider 扫描、SQLite 迁移等）。」

S6（交给 speckit-implement 前）: 「执行 tasks.md。每 task 遵守 .claude/skills/oryx-spec/references/gates.md
    的过程纪律与停止清单；新建 Maven 模块时用 java-spring-init 且跳过 commit；
    不自动 commit / push / 运行 package.sh。」
```

## 禁止事项

- 禁止把宪法 9 条原则、陷阱表、review 清单的正文复制进 SKILL.md / references 正文
- 禁止在本 skill 内另写一份"简化版宪法"（读者永远回源文件）
- java-spring-init 的 `constitution-checklist.md` 若与 `.specify/memory/constitution.md` 冲突，以宪法文件为准
