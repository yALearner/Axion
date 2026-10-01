# Quickstart 验证指南: 002-react

本指南是**验证/跑法说明**，不是实现文档。实现在 `tasks.md` 与实现阶段产出。

## 前置条件

1. 分支 `002-react`（自 `001-provider` 拉出），JDK 21，Maven 可用
2. 001 已交付并全绿（ProviderService / Profile / Message / AxionTool / ToolResult / llm_calls）
3. 每次构建前导出环境（本机未配置 JAVA_HOME/PATH）：
   ```bash
   export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot"
   export PATH="$JAVA_HOME/bin:/d/tools/apache-maven-3.9.16/bin:$PATH"
   ```
4. 本节验证**不需要任何 API key**——模型与工具全部 mock，不碰网络

## 日常验证（默认，秒级）

```bash
mvn test
```

预期：7 个测试类全绿（坑↔测试对号表）：

| 测试类 | 关键回归点 |
|--------|-----------|
| `ReActLoopTest` | 无 tool 调用一轮收尾；有 tool 调用 → 执行并回填下一轮；**坑一**：恰好 10 轮（`verify(chat, times(10))`）+ 返回含"达到最大轮数"；**坑三**：每轮响应与工具结果按序累积；**坑六**：工具经 ToolExecutor 恰好执行一次，无框架自动执行路径 |
| `PromptBuilderTest` | 四部分顺序；**坑二**：超 N 轮截断、不切断一轮内 tool 调用链；system 末尾含当前日期时间；长期记忆段跳过 |
| `ToolExecutorTest` | 成功写审计 success=true；**坑七**：失败也写 success=false + 原因、异常不吞；`retryable` 回传；无内部自动重试 |
| `AgentServiceTest` | 处理期间 ProfileContext 可取到；**坑四**：抛异常 finally 也清；结束后 `sessionManager.save(session)` 被调 |
| `ContextLoaderTest` | **坑五**：改文件后下一次读取立即生效（无缓存）；显式引用缺失报错；Bootstrap 缺失至少 WARN |
| `SessionManagerTest` | 三元组幂等（两次 getOrCreate 同一实例）；三元组任一不同则不同 Session；**session_id 拼接只发生在 SessionManager 一处**（H4 不变量四） |
| `ToolInvocationRepositoryTest` | **坑八**：测试执行手工 schema.sql 建表（不用 Hibernate 自动建）；`tool_invocations` 可存可读、`success`/`error_message` 两列真实存在 |

## 完成定义

```bash
mvn clean verify     # 全绿（含静态检查门禁）才算实现完成
```

## 人工验证（做完怎么验，机器判不了的部分）

1. **Demo 一（每日天气）对话版真模型跑通**：多轮对话里 Agent 调了 http_get、拿到数据、给出穿搭建议。
   注：`http_get` 归第 20 节——本节可先用最小临时工具验证循环（临时代码不进交付物），
   工具就位后按 001 quickstart 同款命令补跑完整 Demo，如实记录。
2. **人工 review**：确认循环是自己实现的、没用框架现成的 Agent 封装（code review 确认，测不出来）。
3. **审计核对**：打开 `.axion/axion.db` 核对一次真实调用后 `llm_calls` / `tool_invocations` 记录与调用实际一致。
