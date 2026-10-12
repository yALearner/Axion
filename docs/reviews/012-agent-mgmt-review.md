# 012-agent-mgmt 代码 review 指南

> 全景调用链 / 逐文件梳理 / 重点清单 / 刻意留白 / 建议顺序 / 验收状态（010-review 同款六段式）

## 一、全景调用链

```
三条录入路径（坑十七）：
  管理台/API：POST /api/v1/agents → AgentApiController（404/重名预检，坑十八）
              → AgentLifecycleService.create（脚手架写目录 → deriveProfile → doRegister，失败回滚坑十一）
  手工丢目录：WorkspaceWatcher（守护线程：启动全量扫 + WatchService 监听 .axion/agents/）
              → register(agentDir) —— 与 API 完全同一段注册代码
  启动扫描：  CliAgentConfiguration.profileRegistry（AgentLoader.loadAll）+ watcher 全量扫（幂等重注册）
同一段注册：doRegister = 校验钩子（011 债②）→ schedules 变更先 unregister 旧句柄 → registry.register → registerProfile
删除：DELETE → lifecycle.delete = unregister（同一段注销代码）→ archive(.axion/archive/，坑十顺序钉死）
生成：generate-files → LlmGateway.chat（落 llm_calls）→ AgentLoader.split 校验（坑十四）→ 剥围栏 → 草稿不落盘
     保存：files → split 预校验（不写坏目录）→ writeAll → deriveProfile → doRegister（写入即生效）
文件：WorkspaceApiController tree/file 读/file 写——防穿越 normalize+startsWith（坑十二）；AGENT.md 编辑走 update 重注册（坑十三）
记忆：ToolExecutor.execute(sessionId, agentName, call) 置入 ToolExecutionContext（finally 清除，坑十六）
      → MarkdownMemoryStore 运行时解析 agents/<name>/MEMORY.md（无上下文回退全局）；readAll 读路径同款临时置入
会话：admin:console:<name> getOrCreate 幂等 → AgentService.process（宪法 VIII）
```

## 二、逐文件梳理

| 文件 | 改动性质 | 看什么 |
|------|---------|--------|
| `core/AgentLifecycleService.java` | 新增（编排者） | doRegister 是唯一注册落点（坑十七）；create 回滚只删自己写的目录；delete/unregister 共用注销代码；generateFiles 的 MODEL_MISSING_MESSAGE 前缀是 web 503 映射契约；update/saveFiles 不再各自 unregister（双注销 bug 已被 InOrder 测试钉回） |
| `core/WorkspaceWatcher.java` | 新增（守护线程） | 事件处理 DELETE 分支**不判 isDirectory**（目录已不在）；registerSafely 有限重试只在守护线程内 sleep（宪法 VII）；isWatching 测试钩子；stop() 关 WatchService 唤醒 take() |
| `core/AgentStore.java` | 新增（目录读写） | writeAll 相对路径越界校验；archive 不物理删；脚手架 tools/schedules 留空（最小权限）；delete 仅回滚用 |
| `core/AgentScheduler.java` | 010 演进 | unregisterProfile：cancel(false) + 移除句柄/登记，**不动 taskLocks、不动 scheduled_tasks 表**（债① 后半归后续节） |
| `core/ToolExecutor.java` | 002 演进 | 三参 execute 置入/清除 finally（坑十六）；两参重载保留（006 契约不回退） |
| `core/ToolExecutionContext.java` | 新增 | ThreadLocal 三方法；无组件注解 |
| `core/MemoryService.java` | 006 演进 | +readAll(agentName)（SPI 零改动承诺的落点） |
| `memory/MemoryServiceImpl.java` | 006 演进 | buildContext/readAll 委托 load 前后临时置入并复原——置入范围只包 store.load（最小窗口） |
| `memory/MarkdownMemoryStore.java` | 006 演进 | 构造改 workspaceRoot、每次调用现算路径（不缓存）；append 先建父目录；SPI 方法与锁语义零改动 |
| `cli/CliAgentConfiguration.java` | 装配 | lifecycle 钩子=validateToolRef 单 Profile 版（011 债②）；author 配置缺省链；watcher daemon 线程启动；MarkdownMemoryStore 新构造 |
| `web/api/AgentApiController.java` | 009 演进 | 薄壳三件事（404/重名预检 → 委托 → 信封）；generate-files 的 IllegalArgumentException 按前缀分 503/400；AgentView 不暴露 api_key；DTO 记录抑制只留 EI_EXPOSE_REP |
| `web/api/WorkspaceApiController.java` | 新增 | 防穿越 resolveSafe 唯一校验点（坑十二）；isAgentMarkdown 走 update（坑十三）；FileNode 递归树 |
| `web/api/AgentManagementException.java` + `GlobalExceptionHandler` | 新增/演进 | 单异常类携带 ErrorCode（code 各自区分 2.5 口径）；wire errorCode 仍为 HTTP 状态码（009 信封形状不变） |
| `web/api/MemoryApiController.java` | 退役删除 | 全局 GET /api/v1/memory 移除（5.2.1 拍板，不留双路径） |
| `boot/application.yaml` | 配置 | axion.author.provider/model（坑十五注释语义） |
| `frontend/`：AgentView/AgentDetailView/FileNode/api.js/main.js/App.vue、MemoryView 移除 | 前端 | 详情 5 tab；api.js 四法双信封单一解析点；深蓝 token 沿用；三态规范 |
| 测试 9 类（新增 5 + 扩展 4 + 前序 IT 2 处重指） | harness | 坑十~十八 9/9 对号（见下） |

## 三、重点 review 清单（按风险排序）

1. **doRegister 的重注册语义**（core/AgentLifecycleService）：schedules 相等 → 只更新注册表不动句柄（防 id 冲突）；不等 → unregister 旧 + register 新。update/saveFiles 若再自行 unregister 会双注销（已在实现中修掉——InOrder 测试钉住）。
2. **WorkspaceWatcher 事件处理**：DELETE 分支不判 isDirectory（目录已删）；重试 sleep 只在守护线程；半成品目录的 3×1s 重试上限不拖垮监听。
3. **generateFiles 的 503/400 映射契约**：MODEL_MISSING_MESSAGE 前缀匹配——lifecycle 改文案 web 端即断，两端必须同步（GenerateTest/AgentApiControllerTest 双端钉）。
4. **ToolExecutionContext 置入/清除成对**：ToolExecutor finally + MemoryServiceImpl try/finally——任何路径必清（坑十六）；两参 execute 重载传 null（006 契约回退全局）。
5. **MarkdownMemoryStore 路径现算**：memoryFile() 每次调用现算（不缓存路径字段）；锁文件随文件同目录；SPI 契约测试仅改构造行（契约方法一行未动）。
6. **防穿越唯一校验点**：resolveSafe normalize+startsWith(workspaceRoot)——tree/file 读/file 写三入口全经它（坑十二）。

## 四、刻意留白（均为已注记）

- 认证鉴权（谁能建 Agent/读文件——内网假设，扩展阶段随 API Key/RBAC 补）
- multipart/zip 上传、Agent 启用/停用状态位、dry-run、版本历史、多轮追问式细化：扩展阶段（课件 §七）
- 启动加载失败 Agent 的清单可见性：011 债③ 延续（课件未点名端点，观测盲区注记）
- unregisterProfile 不动 scheduled_tasks 表：011 债① 后半（store 增删）归后续节

## 五、建议阅读顺序

spec/plan 摘要 → `contracts/agent-mgmt.md`（端点全表 + 不变量）→ `data-model.md`（错误码/配置/生命周期 L4）→ AgentLifecycleService（核心）→ WorkspaceWatcher → ToolExecutor/ToolExecutionContext → MemoryServiceImpl/MarkdownMemoryStore → AgentStore → 两个 Controller + 异常映射 → 装配 → 测试（坑十~十八对号：AgentLifecycleServiceTest 坑十/十一/十七/十八、WorkspaceWatcherTest 坑十七对称、WorkspaceApiControllerTest 坑十二/十三、GenerateTest 坑十四/十五、ToolExecutorTest 坑十六、AgentApiControllerTest 2.5 口径）→ 前端三文件 → CLAUDE.md 陷阱表 +4 条

## 六、验收状态

- 机器判卷：`mvn clean verify -Dskip.npm` 全 10 模块绿（341 tests——287 存量 + 54 新增/扩展；Spotless/P3C/SpotBugs/FindSecBugs/PMD/ErrorProne 全过，2026-10-02）
- 剩余人工项（quickstart 八步）：① 建 Agent 即上线（真链路）② 丢目录即上线 ③ 一句话重生成 + 保存生效（真 key）④ 目录落对地方 + 删除可追溯 ⑤ 文件浏览器（穿越 400 目击）⑥ 管理台全流程（详情 5 tab + 三态）⑦ 行为一致（三种录入各验一遍）⑧ 真模型到点自跑（每分钟 cron + webhook + 审计逐笔）
- 遗留：main CI security job 按先例因 spring-core CVE 常红（照常勿排查）
