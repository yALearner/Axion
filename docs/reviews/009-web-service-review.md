# 009-web-service 代码 Review 指南

> 生成：2026-09-09（交付后复盘 + 全量门禁实跑闭环后）。
> 复盘全记录见 `specs/009-web-service/flow-status.md`（含修订说明 ⑥ 四维修正、实施级修正 ⑩a~d、archive 拍板 A、SpotBugs 17 项处置），本指南是 review 导航。

## 一、全景：REST 接入 → 薄 Controller → 统一引擎

```
业务系统 REST（10 端点，统一前缀 /api/v1）
  → 六个薄 Controller（校验/包装/错误三件事之外零业务——FR-1）
      写路径：POST /sessions、POST /sessions/{id}/messages、POST /agents/{name}/invoke
        → 32KB 防呆 → SessionManager.get(id)（⑩a：现状已有，改造点 a 撤销）
        → runWithTimeout（FutureTask + virtual thread，60s——⑨c）→ agentService.process（宪法 VIII 与 CLI 同入口）
      读路径：GET /profiles /tools /memory（LongTermMemoryStore.load 直连）/health /info（Binder 脱敏重读 providers）
      归档：DELETE /sessions/{id} → SessionManager.archive（拍板 A）
  → 异常单出口 GlobalExceptionHandler（双信封拍板 B：成功 ApiResponse / 错误 ErrorResponse）：
      400 InvalidRequest / 404 Session·Resource / 503 仅 Provider+ServiceUnavailable（⑨a 收紧）/
      504 AgentTimeout / 500 兜底「服务器内部错误」不泄漏内幕（最值钱回归兼 ⑨a 一测双钉）
  → serve 真启动（8080 + virtual thread + 排除 7 个 Spring AI eager 装配类——单 key 可启动，⑩d H3 类名）
  → /admin 只读管理台（Vue3+Vite 五页，frontend-maven-plugin 构建拍板 B + SPA 回落 + axion-admin-ui skill）
```

⑨d 并发面：`AgentService` per-session ReentrantLock——三触发源（CLI/Web/钟推）第一次同场，同会话串行化（排队语义，Web 60s 超时兜底）。

## 二、逐文件梳理

### axion-web/com/axion/web/api（6 Controller + 5 异常 + 2 扩展 + 1 配置）

| 文件 | 关键点 |
|------|--------|
| `SessionApiController` | 4 端点；32KB/100 条防呆；`runWithTimeout`（⑨c：超时后任务体继续跑完、业务异常 unwrap 按原类型上抛——THROWS 抑制 justified）；DTO record 同文件 |
| `AgentApiController` | invoke 一次性 Session 三元组 `("web","invoke",name)` 跑完不缓存（⑨）；Agent 不存在 404 |
| `ProfileApiController` / `ToolApiController` / `MemoryApiController` / `SystemApiController` | 4 查询 Controller；GET /memory 直连 store（实现级明确不扩门面）；GET /info **脱敏**（Binder 重读 axion.providers 只输出 name/baseUrl/model，不含 apiKey）；EI_EXPOSE_REP2 抑制（004 先例，注入单例只读） |
| `GlobalExceptionHandler`（地基扩展） | 保留地基全部既有映射 + 新增 400/404/503/504 + 500 兜底；**⑨a：503 仅 Provider 语义类**（IllegalStateException 归 500——001/006/008 业务校验异常不被报成 Provider 故障）；地基文案「服务器内部错误」保真 |
| `ErrorCode`（地基补值） | +GATEWAY_TIMEOUT(504)，4 值扩 5 值 |
| 5 异常类 | RuntimeException 子类，javadoc 注明映射状态码与语义 |
| `AdminSpaConfig` | /admin/** 静态资源 + 未命中回落 index.html（/api/v1/** 不受影响） |
| `ApiResponse`/`ErrorResponse`/`ServiceUnavailableException` | **地基零删除**（拍板 B，git diff 空） |

### 前端工程 axion-web/src/main/frontend/

| 文件 | 关键点 |
|------|--------|
| `src/api.js` | **双信封统一请求封装**（拍板 B 前端侧防线）：content-type 校验（非 JSON 给可读错误）+ errorCode 分支——页面不手写两套解析 |
| `src/App.vue` + 五视图 | 左侧导航五页、只读无写按钮、三态（空/加载/错误+重试）、token 照抄 skill |
| `vite.config.js` | base '/admin/' + outDir static/admin + `server.host: '127.0.0.1'`（⑩：Windows Node 默认 IPv6 导致 localhost 拒绝连接的修复） |
| `package.json` / `.nvmrc` | vue/vue-router/vite 锁版；node v22.11.0 与 pom 同步（⑨ P2） |

### 前序公共接口改造（经拍板）

| 文件 | 关键点 |
|------|--------|
| `AgentService`（002） | **⑨d per-session 锁**：ConcurrentMap<sessionId, ReentrantLock> + process 内 lock/unlock——三触发源同场串行化；并发回归 maxActive==1 钉死 |
| `SessionManager`（002） | +archive(String)（拍板 A：库里行 archived + 缓存移除）；`get(String)` 现状复用（⑩a 改造点 a 撤销） |
| `SessionEntity`（002 存储） | +archive(Instant) 状态流转方法（实体无 setter 收口先例 updateHistory 同款） |
| `ServeCommand`（003） | web(SERVLET) 真启动、去占位输出 |
| `application.yaml`（boot） | server.port 8080 + autoconfigure.exclude 7 类（⑩d：spring-ai 1.1.8 实际类名 org.springframework.ai.model.openai.autoconfigure.*，课件旧名过时） |

### 测试（5 文件）

| 文件 | 关键点 |
|------|--------|
| `SessionApiControllerTest` | standalone MockMvc（⑩：web 无主类，@WebMvcTest 切片需 @SpringBootConfiguration 花招——standalone 语义等价且更直）；32KB→400 / 不存在→404 / **process 恰调一次** |
| `AgentApiControllerTest` | invoke 三元组 verify + process 恰一次 + 不存在 404 |
| `GlobalExceptionHandlerTest` | 映射五类状态码 / **信封边界**（错误均 ErrorResponse、无 code 字段）/ **最值钱兼 ⑨a 一测双钉**（IllegalStateException→500 非 503 + 统一话术 + 不含 jdbc 内幕）/ 兜底 |
| `WebSmokeIT` | @SpringBootTest 真上下文四端点可达——JPA 扫描红线（18 节坑复发第一时间红）；@DynamicPropertySource 整元素覆盖（008 坑表） |
| `AgentServiceTest`/`SessionManagerTest` 增补 | ⑨d 并发回归（maxActive==1）/ ⑨b 发消息后 get 可见最新 |

## 三、重点 review 清单（按风险排序）

1. **⑨d per-session 锁**（`AgentService.java`）：锁在 process 最外层、finally 放锁——三触发源第一次同场的并发面；排队语义 + Web 60s 超时兜底是自洽组合（超时后任务体继续跑完、锁随后释放）
2. **⑨a 503 收紧**（`GlobalExceptionHandler`）：课件骨架 `{IllegalStateException, ProviderUnavailableException}`→503 全域映射与 001/006/008 业务校验冲突——语义污染修复；一测双钉回归
3. **runWithTimeout unwrap**（两 Controller）：ExecutionException 展开按原类型重抛是 GlobalExceptionHandler 正确映射的前提；InterruptedException 恢复中断标志
4. **双信封边界**（拍板 B）：错误只经 Handler 单出口产出 ErrorResponse；信封边界测试（无 code 字段断言）是新端点选错信封的机器防线
5. **GET /info 脱敏**：Binder 重读 axion.providers——只输出 name/baseUrl/model，apiKey 绝不出接口
6. **eager 装配排除**（application.yaml）：7 个类全排除（⑩d H3 实测 1.1.8 类名）；少排一个 chat 类 serve 就索要 key
7. **frontend-maven-plugin**：npm ci 锁版（package-lock.json 已入库）+ `-Dskip.npm` 后端迭代开关 + node 版本 pom/.nvmrc 同步
8. **archive 链路**（拍板 A）：SessionManager.archive → SessionEntity.archive 状态流转 + 缓存移除——DELETE 归档语义完整

## 四、刻意留白（review 时不要当成缺陷报）

1. **Agent 目录增删改端点**（29/30 节）：含一句话生成 AGENT.md——核心阶段「定义 Agent」= 手写目录 + 重启；与调度运行时接口一起扩展补齐（技术方案 §7.3）
2. **Memory 写端点、Tool describe/调用历史、LLM call 历史/token 统计端点**：扩展阶段
3. **认证（内网假设）/ SSE / WebSocket / 限流 / RBAC**：课件「别手痒」清单
4. **GET /sessions 列表端点**：10 端点无会话列表——管理台会话页显示诚实占位（28 节调度管理端点同批补齐）
5. **Provider 连通性探测**：GET /info 状态恒「registered」——真探测归扩展阶段 Prometheus 一并做
6. **504 重试叠加**：客户端重试会叠加第二次 ReAct——幂等/限流归扩展（⑨c 明示）
7. **管理台 UI 视觉微调**：logo/布局优化按用户计划后续迭代（界面优化之后再做）
8. **优雅停机**：serve 关闭时进行中请求/钟推的打断语义——如实注记不做

## 五、建议 review 顺序

1. `GlobalExceptionHandler` + `ErrorCode`（先看懂异常单出口与⑨a 收紧——最值钱回归所在）
2. `SessionApiController`（32KB 防呆 + runWithTimeout ⑨c）→ `AgentApiController`（invoke 三元组）
3. `AgentService` per-session 锁（⑨d 并发面）+ `AgentServiceTest` 并发回归
4. 四个查询 Controller（GET /memory 直连 + GET /info 脱敏）+ `AdminSpaConfig`（SPA 回落）
5. 前端工程（api.js 双信封封装 + 五视图三态）+ `axion-admin-ui` skill
6. `ServeCommand` + `application.yaml`（eager 排除）→ `WebSmokeIT`（真上下文红线）

## 六、当前验收状态

- **全量门禁实跑通过**（2026-09-09，用户实跑确认）：`mvn clean verify` 10 模块 BUILD SUCCESS——223 tests（web 9：SessionApiControllerTest 5 + GlobalExceptionHandlerTest 4；cli 9 含装配断言；core 56 含 ⑨d 并发回归）+ Spotless/SpotBugs/ErrorProne/Checkstyle/PMD 全过；fat JAR 嵌套 web jar 含 static/admin 完整产物
- **实施级修正 ⑩**（如实记录）：a. SessionManager 已有 get(String)（改造点 a 撤销零改造）；b. archive 拍板 A；c. ⑨d Session 无锁→AgentService per-session 锁；d. H3 核实 OpenAi 装配类名（课件旧名过时）
- **SpotBugs 17 项处置**：11 SPRING_ENDPOINT（薄 Controller 抑制 + justification）+ 3 THROWS（unwrap 合法重抛）+ 3 EI（record copyOf + 注入单例抑制）——全部按 004/006/007/008 先例
- **剩余待办**：① serve 实跑人工项（8080 + curl 四命令 + /admin 只读核对 + 干净机器单 key）② Demo 四/五 31 节合并验收 ③ 管理台 UI 视觉微调（用户计划后续迭代）④ GET /sessions 列表端点归 28 节
