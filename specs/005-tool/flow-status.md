# Flow Status: 005-tool

需求文档: docs/requirements/005-tool.md（课件第 20 节：Tool 体系）
feature.json 指针: specs/005-tool/（切换前: specs/004-notify）
分支: 005-tool（自 main 创建，2026-09-05；004 已合并 main）
创建时间: 2026-09-05T21:18:19+08:00

| 阶段 | 状态 | 产物 | 哈希 | 门禁结论 | 备注 |
|------|------|------|------|---------|------|
| S1 specify | done | spec.md | dbd58bdd | —（质量检查 16 项全过，无 NEEDS CLARIFICATION） | 5 个 US（P1~P3）；FR-001~008 + NFR-001~003；checklists/requirements.md 16 项全过 |
| S2 clarify | done | spec.md（无更新，哈希不变） | dbd58bdd | G1: 通过（0 个澄清问题，无未决项） | 10 类覆盖扫描全 Clear；拍板结论与自审修订口径已钉死 |
| S3 plan    | done | plan.md + research/data-model/contracts/quickstart | — | G2: 六条全通过（用户未勾选异议项，按机器预检记录——004 同款先例） | 无跨模块摩擦点；唯一改造点 CliAgentConfiguration（003 FR-10 既定口径） |
| S4 tasks   | done | tasks.md（T001~T025） | — | G3: 通过 + 固定软停点：交付清单比对齐、无缺、多 3 项核对任务（无新类无新概念，用户确认） | 25 任务 8 阶段；US1=MVP；core 零改动（PromptBuilder WARN 兜底 + 装配处启动校验，S4 实测细化）；harness 先行 8 项 |
| S5 analyze | done | 分析报告（对话输出） | — | G4: 通过（0 ERROR / 0 CRITICAL） | 3 MEDIUM + 2 LOW（C1~C4/F1，均为 tasks.md 实现级决策未钉/引用笔误）已修（用户确认）；覆盖率 100% |
| S6 implement | done | 代码（25 任务全完成） | — | 测试: mvn clean verify 全绿（120 tests + 全部静态门禁） | 收尾 DoD 七项全过；2026-09-05；SpotBugs 10 项门禁修复（抑制注解按 001/004 先例 + 参数净化） |

WARNING 记录: （累计 0/3）

人工验收待办（机器已判卷之外的部分，跑法见 quickstart.md 人工验证）:
- [x] `axion tool list` 可见全部注册工具（2026-09-05 用户 PowerShell 实跑：7 工具中文描述正常）
- [x] 方式三真跑（2026-09-05）：临时 @Tool 示例 Bean → `tool list` 出现 `echo_tool` → 按方法论删除。H3 实测记录：spring-ai-autoconfigure-model-tool 1.1.8 只注册 ToolCallingManager、不自动扫描 @Tool beans 成 Provider（Provider 仅接受显式 toolObjects）→ 方式三扫描以**显式装配**落地（CliAgentConfiguration 扫描 bean 类型收集 @Tool beans 构建 Provider，FR-6 契约内形态）；包装链路另有 AnnotatedMethodToolAdapterTest 3/3 覆盖
- [x] 004 遗留补验（2026-09-05）：企业微信群收到「005 工具体系验证消息」；日志铁证完整 ReAct 闭环（第 1 轮含工具请求=true → notify 执行 success=true 370ms → 第 2 轮生成最终回复）——"LLM 对话内自动调 notify"端到端闭环
- [x] Demo 一对话版补跑（2026-09-05）：weather profile 真调 http_get 多轮（第 1 轮失败 3041ms → 模型重试 4 轮成功 → 最终回复含西安真实天气数据与穿搭建议）——ReAct 失败重试闭环实拍。模型行为记录：前两次 prompt 下 DeepSeek 拒绝调工具（"无法联网"措辞），prompt 强化（声明工具能力 + wttr.in 示例 + 禁止猜测）后开始调用——属模型行为差异非代码缺陷
- [x] MCP 失联实机核验（2026-09-05）：不可达 server → WARN 带 server 名 + 工具列表照常 + 配置还原。SDK 行为记录：initialize 失败等默认 20s 超时（启动 25s vs 正常 5.6s）；reactor "Operator called default onErrorDropped" ERROR 堆栈为 SDK 内部噪音（我方 WARN 兜底在其后正常工作）
- [x] 安全窗口留意：演示 Agent 声明保守 tools（default: [http_get, notify]、weather: [http_get]，未进 shell/http_post）已执行；白名单拦截人工验证归 24 节（如实记录）
- （2026-09-05 S5）G4 分析 3 MEDIUM + 2 LOW，全部已修（用户确认）：ShellTools 超时构造注入、HttpGetTool/HttpPostTool 两顶层类、T012 引用改 T017、PermissiveSandbox 24 节删除、T004 契约测试自建 Registry——需求文档 FR-3 同步超时可注入

停止清单触发记录:
- （2026-09-05 S0）分支检查：hook 未配置、当前在 main → 按项目既有 {NNN}-{slug} 约定自 main 新建 005-tool（用户确认）；未提交的 005 设计文档/prompt 补录随工作区带入新分支
- （2026-09-05 S6 收尾自审）方式三装配接线补丁：T019 交付了 AnnotatedMethodToolAdapter 类+测试，但 FR-7 要求的"方式三包装全汇入 ToolRegistry"装配接线遗漏 → CliAgentConfiguration 补 registerAnnotatedMethodTools（ObjectProvider<MethodToolCallbackProvider> 可选注入 + 降级 INFO 跳过）；全量门禁重跑全绿
- （2026-09-05 用户 PowerShell 实测暴露）ShellTools 平台修复：用户终端 PowerShell 无 bash → ShellTools 补三级解析（PATH 探测 → where git 推导 Git 根目录 → 标准安装路径）+ 绝对路径 Git Bash 子进程 PATH 富化（usr/bin 前置，coreutils 可见）；cmd 原生 PATH 模拟环境下 5/5 全绿；需求文档 FR-3 平台假设口径同步更新
- （2026-09-05 用户实跑暴露）ToolListCommand 改造：003 占位轻命令未接 ToolRegistry → 改为重命令（ChatCommand 同款启动模式）读全量列表，实跑输出 7 工具；需求文档交付清单/改造点已补列
- （2026-09-05 用户实跑暴露）方式三降级闭环：boot 上下文无 MethodToolCallbackProvider bean → 用户拍板补 spring-ai-autoconfigure-model-tool 依赖（停止清单第 6 条处置，交付清单已补列）→ H3 实测该 artifact 1.1.8 不提供 @Tool 自动扫描 → 显式装配落地（CliAgentConfiguration 扫描收集 @Tool beans 构建 Provider），实跑验证 echo_tool 可见后删除临时 Bean
