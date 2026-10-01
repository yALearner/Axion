# 接口契约：ProviderService

> 本契约是**跨节契约**：US-2（ReAct 循环）将按此签名调用。修改本契约视为"修改前序节交付的公共接口"，必须停下报告（执行纪律停止清单第 4 条）。

## 调用入口

```java
Response chat(String sessionId, Profile profile, Prompt prompt)
```

| 参数 | 说明 |
|------|------|
| `sessionId` | 发起调用的会话标识（由调用方传入；本模块不管理会话生命周期）——审计落库按此关联 |
| `profile` | Agent 运行时配置；`profile.provider.name` 决定路由 |
| `prompt` | 要发送的内容（消息列表 + 可用工具） |

## 返回结构

| 字段 | 说明 |
|------|------|
| `text` | 模型文本输出 |
| `toolCalls` | 模型请求的工具调用（**只透传、不执行**） |
| `usage` | token 用量（供审计与上层展示） |

## 异常契约

| 异常 | 触发条件 |
|------|---------|
| `ProviderNotFoundException` | `profile.provider.name` 不在显式映射表中 |
| 底层调用异常 | 超时/限流/模型报错——**原样上抛**，不做静默重试或降级 |

## 行为不变量

1. **路由**：按 `provider name → ChatModel` 显式映射表精确命中；禁止扫描容器 Bean 集合区分 Provider
2. **审计**：成功与失败都落 `llm_calls`（失败先落账再上抛）；记录 provider/model/token/耗时/success/error_message/session_id
3. **工具**：`AxionTool` 只翻译为 schema、不执行；Spring AI 自动 tool 执行**必须关闭**（回归测试钉死）
4. **同步**：全程同步阻塞，无异步模型（虚拟线程承载并发）
5. **失败降级**：核心阶段无 fallback/hedge/circuit breaker——失败即报错

## 配置契约

全局层 `axion.providers`（name + `${ENV_VAR}` api-key + 可选 base-url）声明"连不连得上"；
Profile 层 `provider`（name/model/temperature）声明"怎么用"。Profile 引用的 name 必须命中全局层。

## 消费方

- 当前：无（本节是底座第一批业务代码）
- 后续：US-2 `ReActLoop`（编程指南 §4.2）、US-5 Web Service 经 `AgentService` 链路
