# Flow Status: 008-scheduler

需求文档: docs/requirements/008-scheduler.md（课件第 25 节：定时任务钟推；新版 PDF 已 PyMuPDF 提取，修订说明 ①~⑦ 口径全钉）
feature.json 指针: specs/008-scheduler/（切换前: specs/007-sandbox）
分支: 008-scheduler（自 main 创建，2026-09-07；用户拍板「从 main 建」；3 个设计文档文件随工作树带入）
创建时间: 2026-09-07

| 阶段 | 状态 | 产物 | 哈希 | 门禁结论 | 备注 |
|------|------|------|------|---------|------|
| S1 specify | done | spec.md | d985913cffdd9e75a337c7c911e25caec0ecaf438cfb6c5aa90c5f0e2daa74da | — | 2026-09-07 首跑生成；无 NEEDS CLARIFICATION（修订说明 ①~⑦ 口径全钉死） |
| S2 clarify | done | spec.md（更新） | d985913cffdd9e75a337c7c911e25caec0ecaf438cfb6c5aa90c5f0e2daa74da | G1: pass | 0 题澄清——修订说明 ①~⑦ 口径全钉死；grep 无 open question/TODO/待定 |
| S3 plan    | done | plan.md | b88e8a3ddf76c086e3daebb990e9c1151e549c604cd4c8ddf79b2290cf705080 | G2: pass（六条全部用户确认通过，2026-09-07） | research/data-model/quickstart/contracts 同批产物 |
| S4 tasks   | done | tasks.md | b7b9018b5f303572c1973ba46b3874ffb12e7935c436a97f028dfcbf3116a9cf | G3: pass（机器复核）+ 软停点用户确认 | 10 任务；比对结果：交付清单 7 类全齐、缺 0——用户确认（2026-09-08）；多出 T001/T009/T010 为流程承载任务 |
| S5 analyze | done | 分析报告（对话输出） | — | G4: 通过（0 ERROR / 0 CRITICAL） | 2 LOW（C1 课件 24→25 引用笔误 / C2 日志断言措辞冗余）经用户确认修复后复核清零；覆盖 13/13（100%）；tasks.md 修复后哈希 1674d3f26f2edfaed66d2b22ac93e0a3f90688cfcb52e83070e5bee5340fb4e9 |
| S6 implement | done | 代码（10 任务全完成） | — | 测试: mvn clean verify 全绿（2026-09-08，210 tests + 全静态门禁） | 收尾 DoD 七项全过；实施中三处口径修正（修订说明 ⑧：NFR-3 message 属运营配置澄清、Spring 6 cron 六字段 H3 实测、课件锁测试骨架同线程可重入失效→虚拟线程模拟）；SpotBugs 3 项修复（CRLF 两行日志形态 + sanitize、EI_EXPOSE_REP2 按 004 先例抑制——axion-core pom 增 spotbugs-annotations provided 依赖，004/006 同款机械延伸）；ErrorProne LockNotBeforeTry 两处修正；H3 核实 CronTrigger 无公开 getTimeZone→equals 断言 |

WARNING 记录: （累计 0/3）

停止清单触发记录:
- （2026-09-07）S0 分支检查（hook 未配置）→ 用户拍板「从 main 建 008-scheduler」

人工验收待办（机器已判卷之外的部分）:
- ① 真实到点触发一次：schedules 配"每分钟"（cron `0 * * * * *`），到点看到 Agent 自动发起对话、llm_calls/tool_invocations 有账——cron 触发链路本身只能真等一次（harness 测的是注册参数与 runOnce 行为）【未执行，待用户】
- ② 配置驱动体感：改 AGENT.md 的 cron 表达式不用重新编译，重启后按新时间跑【未执行，待用户】
- ③ 端到端预演：完整走一遍"到点自动触发 → ReAct → 审计留痕"，为 31 节两个定时 Demo 踩实地基（Demo 的 http_get 域名需在 007 白名单内——wttr.in 已就位）【未执行，待用户】
- ④ CLAUDE.md 核心数据模型 schedules 示例 `0 8 * * *`（五段）与 Spring 6 六段要求不符（⑧b 实证）——宪法级文件修正提请用户拍板

人工验收更新（2026-09-08 用户实跑）:
- ① 真实到点触发 ✅ 闭环——SchedulerManualIT 路径 B 完整版实拍：`[taskScheduler-1]` 线程真实触发（cron */30 秒六段）→ 3 轮 LLM（第 1/2 轮含工具请求、第 3 轮最终答复）→ llm_calls 3 行 + tool_invocations 2 行（http_get success=true，wttr.in 过 007 白名单），session=scheduler|scheduler|weather-agent 三元组正确——四段证据全绿（用户 PowerShell 实跑，BUILD SUCCESS 54.9s）
- ③ 端到端预演 ✅ 随 ① 一并闭环（完整 ReAct + 双落账 = 31 节 Demo 一地基实拍）
- ② 配置驱动体感 ⚠️ 机器证据覆盖（注册来自 AGENT.md 配置——坑一测试 + ① 实拍 AGENT.md 驱动），"改 cron 重启生效"的手工体感留用户可选
- ④ CLAUDE.md 五段 cron 示例 ✅ 按方案 A 修正（`0 8 * * *` → `0 0 8 * * *` + 注记「Spring 6 六段含秒」）
- 临时 harness SchedulerManualIT 与 axion-boot/.axion 演示数据已删除
