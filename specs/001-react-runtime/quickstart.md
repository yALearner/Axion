# Quickstart 验证指南：ReAct Runtime（第一周）

端到端验证场景，覆盖 SC-001 ~ SC-008。契约细节见 [contracts/](./contracts/)，数据模型见 [data-model.md](./data-model.md)。

## 前置条件

- JDK 21+、Maven 3.9+
- 一个 OpenAI 兼容 Provider 的 API key（DeepSeek 或 Kimi），已设置环境变量
- 可访问公网（LLM API + 白名单内目标 API）

## 一、构建与启动准备

```bash
# 1. 全量构建（含测试）
mvn clean package

# 2. 设置密钥（示例：DeepSeek）
export DEEPSEEK_API_KEY="sk-..."

# 3. 初始化工作区（幂等）
java -jar axion-cli/target/axion-cli-*.jar init
# 期望：创建 .axion/{agents,skills,memory,logs} + AGENTS.md/SOUL.md/USER.md；再次执行提示"已初始化"且不覆盖

# 4. 配置全局 HTTP 域名白名单（Clarification Q2）
# 编辑 .axion/config.yaml：
#   sandbox:
#     http:
#       allowed_domains: ["wttr.in"]

# 5. 创建天气 Agent
java -jar axion-cli/target/axion-cli-*.jar profile create weather
# 期望：.axion/agents/weather/AGENT.md 模板生成

# 6. 编辑 AGENT.md：provider.name=deepseek、model=deepseek-chat、
#    api_key=${DEEPSEEK_API_KEY}、tools=[http_get]、正文写天气助手指令
```

## 二、验证场景

### 场景 1：多轮对话 + 上下文保持（US-1，SC-004）

```bash
java -jar axion-cli/target/axion-cli-*.jar chat --profile weather
> 你好，我叫小王，我在杭州
# 期望：Agent 回应并记住
> 我刚才说了什么？
# 期望：正确引用"小王/杭州"
/quit
```
交互期间连续输入 ≥3 轮；重复抽测 10 组（自动化用例 ≥9/10 通过；Demo 人工判定目标 100%）。

### 场景 2：ReAct 自主调用 http_get（US-2，SC-002 第一周 Demo）

```bash
> 查一下杭州现在天气怎么样
# 期望：Agent 自主调用 http_get（wttr.in）→ 基于真实返回数据总结天气；全程零干预
```

### 场景 3：单条消息模式（US-4 场景 3）

```bash
java -jar axion-cli/target/axion-cli-*.jar chat --profile weather --message "今天适合出门吗"
# 期望：输出单条回复后进程退出（exit 0）
```

### 场景 4：白名单拒绝（US-2 场景 6）

Agent 的 `sandbox.allowed_domains` 声明（覆盖全局）或两级均未配置时，让 Agent 查一个未白名单域名：
期望：Tool 调用被拒绝、Agent 收到带错误信息的失败结果、不静默放行。

### 场景 5：配置错误零静默（SC-006）

| 操作 | 期望 |
|------|------|
| 不设 `DEEPSEEK_API_KEY` 就 chat | stderr 指明具体环境变量名，exit 1 |
| AGENT.md 声明不存在的 provider | 启动校验错误日志；该 Agent 不可用、其他 Agent 正常 |
| frontmatter 非法（缺 name/正文为空） | 同上，不阻断其他 Agent |
| chat 未指定 --profile 且 0 或多 Agent | 报错并列出可用 Agent |

### 场景 6：审计落库（SC-008，FR-022）

完成一次含 Tool 调用的对话后：

```bash
sqlite3 .axion/axion.db "select count(*) from llm_calls; select count(*) from tool_invocations;"
```
期望：每次 LLM 调用与每次 Tool 调用（含失败）各一行；抽任意一次对话可还原调用链。

### 场景 7：多 Agent 并存不串号（SC-007，US-3）

`profile create` 第二个 Agent（不同 provider/模型，若有第二个 key）→ 分别 `--profile` 对话：各自按自己的 provider 配置路由。

## 三、自动化测试

```bash
mvn test
```

关键确定性用例（不依赖真实 LLM，用 FakeChatModel 脚本化响应）：
- ReAct 三种终止条件（SC-003）：无 Tool 调用直接返回 / Tool 链完成后返回 / `max_iterations` 强制结束
- 截断规则：超 `max_history_turns` 后仅注入最近 N 轮且工具调用链不被切断（Clarification Q1）
- `http_get` 状态码语义（WireMock 桩，Clarification Q4）：2xx 成功；404 直接失败不重试；500 内部重试 3 次后失败且 retryable=true
- 白名单覆盖语义（Clarification Q2）：Agent 级声明替代全局、未声明继承全局、两级未配置 fail-closed
- 审计两表写入（SQLite 临时文件库）

真实 Provider 的多轮抽测（SC-004 自动化部分）为独立 profile（需 API key 环境变量，缺失时跳过）。

## 四、验收对照

| SC | 验证方式 |
|----|---------|
| SC-001 | 全新环境从 `init` 到 chat 首句回复 ≤5 分钟（秒表） |
| SC-002 | 场景 2 演示 |
| SC-003 | 自动化三用例 |
| SC-004 | 自动化 ≥9/10 + 场景 1 人工 Demo |
| SC-005 | 单次消息内部开销 ≤50ms（性能用例） |
| SC-006 | 场景 5 |
| SC-007 | 场景 7 |
| SC-008 | 场景 6 |
