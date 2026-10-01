# Flow Status: 002-react

需求文档: docs/requirements/002-react.md（US-2 对应，课件第17节对齐版）
feature.json 指针: specs/002-react/（切换前: specs/001-provider）
分支: 002-react（自 001-provider 创建，2026-09-01）
创建时间: 2026-09-01

| 阶段 | 状态 | 产物 | 哈希 | 门禁结论 | 备注 |
|------|------|------|------|---------|------|
| S1 specify | done | spec.md | a3375e45 | —（质量检查全过，无 NEEDS CLARIFICATION） | 6 个 US；FR-001~013；checklists/requirements.md 16 项全过 |
| S2 clarify | done | spec.md（无更新） | a3375e45 | G1: 通过（0 个澄清问题，无未决项） | 10 类覆盖扫描全 Clear；低影响项 defer 到 plan |
| S3 plan    | done | plan.md + research/data-model/contracts/quickstart | — | G2: 六条全通过 + 2 摩擦点拍板（用户确认） | 摩擦点 A: LlmGateway 端口；摩擦点 B: ToolSchemaAdapter 迁 core；文档已同步 |
| S4 tasks   | done | tasks.md（T001~T028） | — | G3: 通过 + 固定软停点：交付清单比对齐、无缺无多、harness 先行（用户确认） | 28 任务 9 阶段；US1=MVP；Sandbox 无独立测试类（用户确认照交付清单） |
| S5 analyze | done | 分析报告（对话输出） | — | G4: 通过（0 ERROR / 0 CRITICAL） | 1 MEDIUM + 2 LOW 经用户确认全部修复（T013/T014 日志措辞、T019 空工具用例、Phase 2 重排编号） |
| S6 implement | done | 代码（28 任务全完成） | — | 测试: mvn clean verify 全绿（47 tests + 全部静态门禁） | 收尾 DoD 七项全过；2026-09-02 |

WARNING 记录: （累计 1/3）C1 结构化日志无显式任务（MEDIUM）→ 已修（T013/T014 补日志要求）；A1 空工具列表用例（LOW）→ 已修（T019）；F1 Phase 2 编号错位（LOW）→ 已修（重排）

停止清单触发记录:
- （2026-09-01 S0）分支检查：当前在 001-provider（前序 feature 分支）→ 按用户已记录决策「从 001-provider 拉 002-react 分支」执行，未再询问
- （2026-09-01 S3）停止清单第 1/4 条：ReActLoop/PromptBuilder（core）需消费 001 交付物（ProviderService/ToolSchemaAdapter）但依赖方向禁止反向依赖 → 用户拍板「LlmGateway 端口接口 + ToolSchemaAdapter 迁 core」；需求文档交付清单补 LlmGateway、新增改造点节，CLAUDE.md/TechnicalSolution §10 同步
- （2026-09-02 人工验收）发现工程缺口：AxionApplication 的 scanBasePackages 不作用于 JPA 仓储/实体扫描（AutoConfigurationPackages 仍为 com.axion.boot），com.axion.storage 的 Repository/实体在真实启动时不会被注册——临时 harness 以 @EnableJpaRepositories/@EntityScan 绕过 → **用户拍板「现在修」**：AxionApplication 已补两注解（fix 提交随 PR #2），harness 已删除

人工验收待办（机器已判卷之外的部分）:
- [x] Demo 一（每日天气）对话版真模型跑通：2026-09-02 真实调用通过——3 轮 LLM 思考 + 2 次 http_get（临时工具，wttr.in）+ 中文穿搭建议；llm_calls=3 / tool_invocations=2 真实落库（axion-boot/.axion/axion.db，测试工作目录所致；生产 java -jar 从仓库根启动时落根目录 .axion/）
- [x] code review：循环自实现确认——core/boot 主代码 grep 无 ChatClient/ToolCallingManager/executeToolCalls；ReActLoop 96 行
- [x] axion.db 审计核对：3 条 llm_calls（success=true、session 关联正确、durationMs 记录）+ 2 条 tool_invocations（success=true、result 真实内容）
- [ ] 第 20 节 HttpTools 就位后按 quickstart 补跑完整 Demo（临时工具替身退役）；临时 harness ReActDemoManualIT 已删（2026-09-02 用户拍板）

