# 宪法核对清单（执行本 skill 时逐条过）

> 适用对象：项目根 `CLAUDE.md`「不可违背的原则」/ `.specify/memory/constitution.md`。
> 规则：本 skill 只装工程地基，不实现业务；发现冲突停下报告，不静默覆盖。
> 下表为 Axion 9 条示例（2026-08-26 核对结果见「本次核对」列）。

| # | 原则 | 与本 skill 的关系 | 本次核对 |
|---|------|------------------|---------|
| I | 自实现 ReAct Loop | 不涉及（业务范围） | ✅ 未触碰业务代码 |
| II | Spring AI 只用协议转换 + @Tool schema | 步骤 2 升级 Boot 3.5.16 时须验证 spring-ai 1.0.0-M4 兼容 | ✅ 编译/测试通过；版本上界冲突已钉（见 tool-versions.md） |
| III | Provider 显式映射 | 不涉及 | ✅ |
| IV | 一个目录=一个 Agent；Skill 渐进披露 | 本 skill 即按该形态存放（.claude/skills/） | ✅ |
| V | 审计表 Day One 写入 | 结构化日志（步骤 3）是审计落库的补充而非替代；不改业务写入逻辑 | ✅ 日志补充，未动存储代码 |
| VI | 不用 SecurityManager；Sandbox 接口先行 | 安全检查（步骤 8）不引入 SecurityManager 类方案 | ✅ |
| VII | 同步执行模型 + 虚拟线程 | 步骤 5 确认 `spring.threads.virtual.enabled=true` 已开 | ✅ 已存在，未改动 |
| VIII | 三触发源共用一个引擎 | 不涉及 | ✅ |
| IX | Tool 模块三合一 | 不涉及（模块结构已存在，步骤 1 只核对不动） | ✅ 9 模块骨架原样保留 |

## 通用核对点

- [ ] 敏感配置全用 `${ENV_VAR}` 占位（application.yaml、AGENT.md 模板、CI secret）
- [ ] 生成的文件不覆盖项目已有约定（如已有 ApiResponse/日志规范则沿用）
- [ ] 依赖方向保持单向（storage ← core ← 能力层 ← 组装层 ← boot）
- [ ] 冲突裁决：风格以 GJF 为准；规约冲突按阿里规约；宪法冲突停下报告
