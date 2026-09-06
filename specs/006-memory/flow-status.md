# Flow Status: 006-memory

需求文档: docs/requirements/006-memory.md（课件第 21/22 节：Memory 三层记忆；整体以 D:\项目\ 新版 PDF 为准）
feature.json 指针: specs/006-memory/（切换前: specs/005-tool）
分支: 006-memory（自 main 创建，2026-09-06；005 已合并 main）
创建时间: 2026-09-06T13:44:13+08:00

| 阶段 | 状态 | 产物 | 哈希 | 门禁结论 | 备注 |
|------|------|------|------|---------|------|
| S1 specify | done | spec.md | 5ef860268807f29e6868af909b22888eec805b29cf2287887b94a757bd0524c5 | — | 2026-09-06 首跑生成；续跑时机器核对一致性通过 |
| S2 clarify | done | spec.md（更新） | 5ef860268807f29e6868af909b22888eec805b29cf2287887b94a757bd0524c5 | G1: pass | 澄清答案由设计期拍板 #1~#9 覆盖；grep 无 open question |
| S3 plan    | done | plan.md | 239f603ed97695ee70256c03309bb34ed5794ff6c0a235a8439f80313d43bd27 | G2: pass（六条全部用户确认通过，2026-09-06） | research/data-model/quickstart/contracts 同批产物 |
| S4 tasks   | done | tasks.md | 02825225fc23490841d0b9aa6ae859f55e1f0f6844bfb6422ed9b580ac17d432 | G3: pass（机器复核）+ 软停点用户确认 | 37 任务；比对结果：齐+多 3 类，缺 0——用户确认（含 T031）；G3 ⚠️ cli 无测试经用户指示补强（T010/T025），四模块测试全覆盖 |
| S5 analyze | done | 分析报告（对话输出） | — | G4: 通过（0 ERROR / 0 CRITICAL） | 2 MEDIUM + 2 LOW（C1/C2/U1/C3）经用户确认全部修复后复核清零；覆盖 15/16（94%）；tasks.md 修复后哈希 aba474baa5902789a663e3ce713af9528b8c435ae31d285824abef45307e0774 |
| S6 implement | done | 代码（37 任务全完成） | — | 测试: mvn clean verify 全绿（342 tests + 全部静态门禁，2026-09-06） | 收尾 DoD 七项全过；SpotBugs 2 项修复（CRLF 日志净化 + EI_EXPOSE_REP2 抑制，004 先例）；T036 跨模块自动纳入不成立→MemoryToolsTest 等价覆盖（坑十二同款断言） |

WARNING 记录: （累计 0/3）
- S5 分析 2 MEDIUM + 2 LOW（2026-09-06，全部已修——用户确认「修复全部 4 条」）：C1 NFR-003 日志隐私无显式任务 → T034 增补 grep 核对 + Notes 纪律条目；C2 spec US3-AC4/FR-6 故障路径（文件不可读/库损坏）无断言任务 → T018/T023 增补 FR-6 故障断言；U1 Mem0 metadata 分区 key 未钉 → T030 随 H3 核实钉死并记录；C3 SC-004 上层零改动无机械断言 → Notes 增补 US3 后 git diff 目检

人工验收待办（机器已判卷之外的部分，跑法见 quickstart.md 人工验证）:
- [x] **Demo 二对话版（2026-09-06 用户实机跑通）**：① 写链——memo-writer 第 1 轮即含工具请求（1406ms）→ save_memory success=true（5ms）→ MEMORY.md 归档区落条目「用户项目用 Spring Boot，部署在 K8s 上」；② 读链——memo-reader 全新 session（sessions 表两个独立 session_id 实证）连调 2 次 recall_memory（keyword=数据库→未命中、keyword=用户偏好→命中，坑十八未命中友好措辞间接实证）→ 最终答复开篇「基于我的长期记忆，我了解到你的项目使用 Spring Boot 并部署在 K8s 上」并推荐 PostgreSQL 为主；③ 审计——tool_invocations 落 3 行（save×1 + recall×2）全 success=1、session 关联正确、durationMs 有值；④ 两次对话为两个独立 JVM 进程 = 跨进程持久化实证
- [x] 审计落库核对（2026-09-06 jshell 查库）：见上 ③，input_json 含 content（记忆明文副本为已知设计）
- [x] sqlite 档实机（2026-09-06 用户实跑 + jshell 查库）：`AXION_MEMORY_BACKEND=sqlite` 启动装配正常（schema.sql 自动建 memory_entries 表）；真模型 save_memory 写入 → 查表 id=1 content=GitLab CI 部署、scope=ARCHIVAL（缺省 ✓）、created_at ISO-8601 ✓；换档隔离——MEMORY.md 无 GitLab 条目（sqlite 档未碰 markdown 文件）；审计 id=11 success=1
- [x] mem0 档故障快速失败实机（2026-09-06 环境变量覆盖跑通）：无凭证启动报错「需要环境变量 MEM0_BASE_URL 与 MEM0_API_KEY」；不可达地址 chat 快速失败「Mem0 读取失败: I/O error on GET http://127.0.0.1:9/memories」不静默不降级（请求路径无 /v1/ 前缀 = H3 结论再实证）
- [x] 非法 backend 值启动报错（2026-09-06 实跑）：`backend=bogus` 启动失败，异常根因「axion.memory.backend 非法值: bogus（取值 markdown/sqlite/mem0）」不静默
- [ ] 真实自托管 Mem0 实例验证：本地无实例 → **如实记待办**（mock 层已验协议翻译；验证口径见需求文档自审拍板）；**详细跑法已沉淀进 `quickstart.md`「mem0 真机验证跑法」（五步：Docker 起实例 → env 换档写入 → mem0 侧核对 → 语义检索验证 → 预期差异口径与回写）**
- [x] 无 yaml 改动（全程 `$env:AXION_MEMORY_BACKEND` 会话级覆盖）→ 无需恢复，默认仍 markdown

停止清单触发记录:
- （2026-09-06 S0）分支检查：hook 未配置、当前在 main → 按项目既有 {NNN}-{slug} 约定自 main 新建 006-memory（用户确认）；未提交的 006 设计文档/SKILL.md 课件路径更新随工作区带入新分支
- （2026-09-06 续跑 S0）flow-status 全阶段 pending 且无哈希、但 S1/S3 产物已齐 → 续跑校验无法机器完成，停下报告；用户确认「续跑 from S4」：现有 spec/plan 经机器一致性核对（spec FR-001~008 ↔ 需求文档 FR-1~8、plan 9 模块结构）后补录本表，G2 六条人工确认补执行
- （2026-09-06 S4）软停点比对后用户指示「帮我完善」G3 ⚠️（cli 模块无测试）→ tasks.md 增补 T010/T025（CliAgentConfigurationTest 装配断言 + 换档断言，含 cli pom test 依赖），四模块测试覆盖补齐，重新比对无缺口
- （2026-09-06 S6 T034）US4 边界核对：① USER.md 唯一写点为 003 InitCommand 幂等模板（内容即「只读：Agent 不写本文件」，非 Agent 写路径）② MEMORY.md 写入仅经 MarkdownMemoryStore（CliAgentConfiguration 仅传路径、InitCommand 仅建模板）③ 记忆内容/检索关键词零进日志参数——三项核对通过，无代码改动
