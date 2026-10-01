# Data Model: 009-web-service

> 本节无新数据表（会话归档复用 002 sessions 表；GET /memory 读 LongTermMemoryStore）。数据形态 = 双信封响应契约（拍板 B）+ 异常映射表 + 端点契约。

## 双信封响应契约（拍板 B，地基类保留）

| 信封 | 字段 | 使用边界 |
|------|------|---------|
| `ApiResponse<T>`（成功） | `code`(int, 0=成功) / `message` / `data`(T) / `timestamp`(Instant) | 所有成功端点响应；`ApiResponse.ok(data)` |
| `ErrorResponse`（错误） | `errorCode`(int, =HTTP 状态码) / `message` / `timestamp` | 所有错误响应，**只经 GlobalExceptionHandler 单出口产出**；`ErrorResponse.of(ErrorCode, message)` |

- **信封边界机器防线**：GlobalExceptionHandlerTest 断言「错误均 ErrorResponse、成功均 ApiResponse」——新端点选错信封测试即红（拍板 B 漂移防线）
- Controller 不得自行 try-catch 拼错误体（FR-2 信封契约）

## ErrorCode 枚举（地基 4 值 + 本节补 1 值）

| 值 | HTTP | 触发场景 |
|----|------|---------|
| BAD_REQUEST | 400 | InvalidRequestException（消息空/超 32KB）；地基 MethodArgumentNotValidException/IllegalArgumentException |
| NOT_FOUND | 404 | SessionNotFoundException / ResourceNotFoundException；地基 NoResourceFoundException |
| INTERNAL_ERROR | 500 | 兜底 Exception——统一话术「内部错误」不泄漏内幕 |
| SERVICE_UNAVAILABLE | 503 | **仅** ProviderUnavailableException + 地基 ServiceUnavailableException（⑨a：IllegalStateException 归 500） |
| **GATEWAY_TIMEOUT（本节补）** | 504 | AgentTimeoutException（Agent 调用 60s 上限） |

## 异常类（本节新增 5 个，落 axion-web/api）

| 类 | 语义 | 映射 |
|----|------|------|
| InvalidRequestException | 请求参数非法（消息空/超 32KB） | 400 |
| SessionNotFoundException | 会话 id 不存在 | 404 |
| ResourceNotFoundException | 通用资源不存在（Agent 名等） | 404 |
| ProviderUnavailableException | Provider 依赖不可用 | 503 |
| AgentTimeoutException | Agent 调用超 60s | 504 |

## 端点与委托（10 端点 → 核心层服务）

| 端点 | 委托 | 关键语义 |
|------|------|---------|
| POST /sessions | SessionManager（getOrCreate 三元组由请求参数生成） | 创建会话，返回 session_id |
| POST /sessions/{id}/messages | `agentService.process(session, content)` | 与 CLI 同一入口（宪法 VIII）；32KB 防呆 |
| GET /sessions/{id} | SessionManager.findById（改造点 a，⑨b 一致性核实） | 历史最多 100 条 |
| DELETE /sessions/{id} | SessionManager（归档，002 语义） | 归档 |
| POST /agents/{name}/invoke | `agentService.process(一次性Session, content)` | 三元组 ("web","invoke",name) 跑完不缓存 |
| GET /profiles | ProfileRegistry.list() | 空表返回空列表 |
| GET /memory | `LongTermMemoryStore.load()`（实现级明确直连） | MEMORY.md 原文 |
| GET /tools | ToolRegistry | 空表返回空列表 |
| GET /health | — | ok |
| GET /info | ProviderService 状态 | 各 Provider 连通状态 |

## 防呆限制（FR-4）

- 单条消息 ≤ 32KB（超 → 400 InvalidRequestException）
- 历史返回最近 100 条
- 防呆不是治理——治理级限流归扩展（NFR-2）

## 前端数据流（管理台 v1 只读）

- 五页 → 五个 GET 端点 → 统一请求封装（axion-admin-ui skill 内置）：成功解析 `ApiResponse.data`、错误解析 `ErrorResponse.errorCode/message`——页面不手写两套解析
- 产物 static/admin/（vite base '/admin/'）→ Spring 托管 /admin → SPA 回落 index.html（/api/v1/** 不受影响）
