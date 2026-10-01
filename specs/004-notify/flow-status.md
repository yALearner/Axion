# Flow Status: 004-notify

需求文档: docs/requirements/004-notify.md（课件第 19 节：Notify 模块）
feature.json 指针: specs/004-notify/（切换前: specs/003-cli）
分支: 004-notify（自 main 创建，2026-09-05；003-cli 已合并 main）
创建时间: 2026-09-05T01:03:02+08:00

| 阶段 | 状态 | 产物 | 哈希 | 门禁结论 | 备注 |
|------|------|------|------|---------|------|
| S1 specify | done | spec.md | 5684e20b | —（质量检查 16 项全过，无 NEEDS CLARIFICATION） | 4 个 US（P1~P3）；FR-001~007 + NFR-001~003；checklists/requirements.md 16 项全过 |
| S2 clarify | done | spec.md（无更新，哈希不变） | 5684e20b | G1: 通过（0 个澄清问题，无未决项） | 10 类覆盖扫描全 Clear；6 条拍板结论口径已钉死 |
| S3 plan    | done | plan.md + research/data-model/contracts/quickstart | — | G2: 六条全通过（用户确认；④~⑥ 未见勾选按全部通过记录） | 无跨模块摩擦点；pom 结构性新增 2 项经用户确认补列需求文档交付清单 |
| S4 tasks   | done | tasks.md（T001~T019） | — | G3: 通过 + 固定软停点：交付清单比对齐、无缺、多 5 项任务形态（无新类无新概念，用户确认） | 19 任务 7 阶段；US1=MVP；审计口径钉死 T012+T010+T016③；harness 先行 6 项 |
| S5 analyze | done | 分析报告（对话输出） | — | G4: 通过（0 ERROR / 0 CRITICAL） | 1 HIGH（C1：课件骨架 @Component × boot 全树扫描冲突）已修（用户确认钉死 tasks.md T011/T012）；覆盖率 100% |
| S6 implement | done | 代码（19 任务全完成） | — | 测试: mvn clean verify 全绿（78 tests + 全部静态门禁） | 收尾 DoD 七项全过；2026-09-05 |

WARNING 记录: （累计 1/3）
- 交付后复盘：偏差与不足分析见 `review-analysis.md`（D1~D5 偏差 + S1~S4/E1/P1~P2 不足，D3 深析含用户拍板论证；S1/S2/S4/E1 已修复；§六 代码风格决策：不引 Lombok + 模块即层维持现状，已同步 CLAUDE.md 设计原则）
- C1（HIGH，已修）：课件骨架 @Component × boot 全树扫描——tasks.md T011/T012 钉死不加组件注解（2026-09-05 用户确认）

人工验收待办（机器已判卷之外的部分，跑法见 quickstart.md 人工验证）:
- [x] 真 webhook 收到消息（2026-09-05：企业微信真实群机器人收到「人工验证消息：004-notify 工具链路」，中文正常；首次验证踩中 errcode 40008 → 方案 A 修复后 errcode 0）
- [x] 临时 harness NotifyManualIT（成功落账带渠道名 + 反例二条 + JPA 映射回读断言，3/3 全绿；已按方法论删除）
- [x] 落库核对（notify_channels 4 列；tool_invocations success/result_json/duration_ms 核对通过；harness 期间发现并修复两坑：① surefire 工作目录实测 = 模块目录 → .axion 父目录先建 ② 数据源必须 @DynamicPropertySource 隔离 target/manual-it，避免 deleteAll 碰共享库）
- [ ] 接口中立性自查（步骤四思维练习：换企业微信官方 SDK 实现，send(NotifyTarget, String) 签名需要改吗？答案应是不需要——留给用户 1 分钟自查）
- [x] 已知待办：LLM 对话内自动调 notify 端到端 → 第 20 节（005-tool）已补验（2026-09-05：chat 里自动调 notify 推送成功，企业微信群收到，完整 ReAct 日志铁证）

停止清单触发记录:
- （2026-09-05 S0）分支检查：hook 未配置、当前在已合并的 003-cli → 按项目既有 {NNN}-{slug} 约定自 main 新建 004-notify（用户确认）；未提交的 docs 改动随工作区带入新分支
- （2026-09-05 S3）停止清单第 1 条：axion-tool pom 迫使新增 axion-storage + spring-boot-starter-test 两项依赖（机器可判结构件，超出交付清单字面）→ 已补列需求文档交付清单与 FR-7（用户确认，003 父命令类先例）
- （2026-09-05 S6）停止清单第 6 条：SpotBugs EI_EXPOSE_REP2 拦截 RestClient 注入（第三方可变接口无法防御拷贝）→ 新增 spotbugs-annotations（provided）依赖 + 抑制注解，已补列需求文档交付清单（用户确认，001-provider 同款先例）；Map 类 3 处用 Map.copyOf 防御拷贝（ProviderService/JsonSchema 先例，无新依赖）
- （2026-09-05 交付后复盘修复）用户拍板修复 review-analysis.md S1/S2/S4/E1：① content 必填校验（不 NPE 不推"null"，+3 测试）② NotifyTarget 键常量+访问器（空串防线）③ 契约不变量 9（timeout 跨节钉死）——需求文档 FR-2/FR-6/骨架/修订说明已同步
- （2026-09-05 人工验收修复）停止清单第 2/3 条：真实企业微信对课件骨架 body `{"content":...}` 返回 errcode 40008（HTTP 200 收不到）→ 用户拍板方案 A：body 改通用 text 格式 `{"msgtype":"text","text":{"content":...}}`（企微+钉钉共用，飞书归扩展）；需求文档 FR-3/骨架/修订说明、spec/contracts/tasks/quickstart/review-analysis 已同步
