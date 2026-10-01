# Contract: Axion Web Service REST API（10 端点 + 双信封）

> 本节对外契约 = 10 端点 + 双信封格式（拍板 B）+ 错误码映射。契约测试承载：`SessionApiControllerTest` + `GlobalExceptionHandlerTest`（信封边界/映射/500 不泄漏）+ `WebSmokeIT`（真实可达）。
> 契约期：10 端点与信封格式自本节起为**跨节契约**——28 节调度管理端点、30 节 Agent 管理页、31 节 Demo 四/五 均消费本契约，此后不改。

## ① 双信封（拍板 B）

```json
// 成功（所有成功端点）
{ "code": 0, "message": "success", "data": <T>, "timestamp": "ISO-8601" }

// 错误（只经 GlobalExceptionHandler 单出口）
{ "errorCode": <HTTP 状态码>, "message": "人类可读", "timestamp": "ISO-8601" }
```

## ② 10 端点（统一前缀 /api/v1）

| 方法 | 路径 | 成功响应 data | 错误 |
|------|------|--------------|------|
| POST | /sessions | session 信息（含 id） | 400 |
| POST | /sessions/{id}/messages | 消息回复 | 400（空/超 32KB）、404、503、504、500 |
| GET | /sessions/{id} | 会话历史（最近 100 条） | 404 |
| DELETE | /sessions/{id} | 归档结果 | 404 |
| POST | /agents/{name}/invoke | 消息回复（一次性 Session） | 400、404、503、504、500 |
| GET | /profiles | Profile 列表 | — |
| GET | /memory | MEMORY.md 内容 | — |
| GET | /tools | Tool 列表 | — |
| GET | /health | ok | — |
| GET | /info | 运行信息 + Provider 状态 | — |

## ③ 错误码映射（GlobalExceptionHandler）

| 状态码 | 异常 | 备注 |
|--------|------|------|
| 400 | InvalidRequestException / IllegalArgumentException / MethodArgumentNotValidException | 参数问题 |
| 404 | SessionNotFoundException / ResourceNotFoundException / NoResourceFoundException | 资源不存在 |
| 503 | **仅** ProviderUnavailableException + ServiceUnavailableException | ⑨a：IllegalStateException 归 500（业务校验不冒充 Provider 故障） |
| 504 | AgentTimeoutException | Agent 调用 60s 上限；超时后任务体继续跑完（⑨c 明示） |
| 500 | 兜底 Exception | 统一话术「内部错误」，不泄漏内幕（最值钱回归） |

## ④ 防呆限制（FR-4）

- 单条消息 ≤ 32KB；历史返回最近 100 条——防呆不是治理

## ⑤ 不变式

- Controller 薄：校验/包装/错误三件事之外零业务逻辑；发消息走 agentService.process（宪法 VIII）
- 审计零新增：llm_calls/tool_invocations 由 AgentService 内部既有路径落账
- 同步阻塞直进直出（virtual thread，宪法 VII）；无 Reactor/CompletableFuture/自建线程池
- 核心阶段不做：认证（内网假设）/SSE/WebSocket/限流/RBAC
- 明确不做（后续节）：Agent 目录增删改（29/30）、Memory 写端点、Webhook、Prometheus、调度管理端点（28）
