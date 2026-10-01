# 需求文档编号规范

## 目录与编号

```
docs/requirements/
└── NNN-slug.md        # NNN = 3 位递增序号；slug = 特性短名（小写 + 连字符）
                       # 与产物目录 specs/NNN-slug/ 一一对应
```

示例：`docs/requirements/002-memory-system.md` → `specs/002-memory-system/`。

> **编号重启（2026-08-30）**：早期 spec-kit 试跑留下的 `specs/001-react-runtime` 是历史产物，
> **不计入新序列**，目录保留不删除。新序列从 `001-provider` 开始，NNN 按新序列递增
> （下一个新需求 = 002-xxx）。编号解析与「取下一个可用序号」一律以新序列为准，
> 不得因历史目录存在而把新需求编成 002 起步之外的值。

## 解析规则（三层回退，按优先级）

| 优先级 | 形态 | 解析结果 |
|-------|------|---------|
| 1 | `NNN-slug` 且 `docs/requirements/NNN-slug.md` 存在 | 新需求 → 全程产物进 `specs/NNN-slug/` |
| 2 | `US-n`（n=1..5） | 需求源 = `docs/AiProgrammingGuide.md` §4.n + `DemandAnalysis.md` 对应能力章节 + 第 13 章验收 Demo；产物目录建议 `specs/NNN-slug/`（NNN 取新序列下一个可用序号，slug 由能力名推导），**需停下请用户确认** |
| 3 | `NNN-slug` 且 `specs/NNN-slug/` 已存在 | 断点续跑模式（提示 `--from`） |

三层都不命中 → 报错退出，输出：

```
无法解析编号「<输入>」。合法形态：
  /oryx-spec 002-memory            # 需求文档（docs/requirements/002-memory.md）
  /oryx-spec US-3                  # 主体阶段 user story
  /oryx-spec 001-react-runtime --from tasks   # 断点续跑

若无需求文档：调用 `/oryx-design <目标模块>` 生成六段式设计文档（优先），
或按本文件「需求文档最小模板」手写（兜底）。设计文档的编写须经人工复核。
```

## 需求文档最小模板

> **优先用完整模板**：`/oryx-design` skill 的 `references/template.md` 提供六段式完整模板
> （背景与价值 / 用户场景 / 功能需求 / 明确不做 / 验收标准 / 依赖与假设），
> `docs/requirements/001-provider.md` / `002-react.md` 是事实模板。本最小模板仅作兜底。

```markdown
# <特性名>

## 背景与目标
（为什么做、解决什么问题、目标用户）

## User Scenarios（用户故事）
1. 作为 <角色>，我希望 <能力>，以便 <价值>
   Acceptance Criteria:（可验证，尽量复用已有验收 Demo 风格）

## Requirements（需求条目，逐条编号 FR-1、FR-2…）
- FR-1: <功能需求，可验证>
- NFR-1: <非功能需求：性能/安全/审计等>

## 交付清单（本需求的对外概念白名单）
- 新模块 / 新类 / 新配置键 / 新表 / 新 REST 路径 / Profile 字段：逐项列出
（停止清单第 1、2 条的判断基准：清单之外的一律先停）

## 改造点（明确列出允许修改的前序公共接口，如有）
## 参考资料
- 对应 docs/ 文档章节链接
```

> 关键：**交付清单是"对外概念"的判断基准**。spec/plan/tasks 产出超出清单的内容，
> 在 S6 触达前必须回到停止清单第 1 条停下来请用户确认。
