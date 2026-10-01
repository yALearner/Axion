# 配置契约（第一周）

两份配置 + 环境变量注入规则。所有敏感凭证只允许 `${ENV_VAR}` 占位，不得明文（FR-003）；`ConfigLoader` 加载时校验，缺失/非法给指明具体变量名的报错。

## 1. `AGENT.md`（`.axion/agents/<name>/AGENT.md`）

frontmatter（YAML，SnakeYAML 解析）+ 正文（任务指令，注入 system prompt）。

```yaml
name: weather-agent          # 必填；目录名
description: 天气助手          # 可选
identity:
  agent_name: 天气小欧         # 可选
  prompt: 你是一个天气查询助手…  # 可选；正文（--- 之后）才是任务指令，两者不重复
provider:
  name: deepseek             # 必填；必须存在于 ProviderService 显式映射
  model: deepseek-chat       # 必填
  temperature: 0.7           # 可选，默认 0.7
  api_key: ${DEEPSEEK_API_KEY}  # 可选；只允许 ${ENV_VAR} 占位，明文直接拒绝加载
tools:                        # 可选；每个条目必须已注册
  - http_get
channels:                     # 可选；本里程碑仅支持 cli
  - cli
sandbox:                      # 可选（Clarification Q2）
  allowed_domains:            # 一旦声明即完全替代全局列表（覆盖语义，非并集）
    - wttr.in
settings:                     # 可选
  max_iterations: 10          # 默认 10
  max_history_turns: 20       # 默认 20
---
你是一个专业的天气查询助手…
```

校验失败（缺 name、正文为空、provider 不存在、tool 未注册、channel 不支持、api_key 明文）→ 错误日志 + 该 Agent 不可用，**不阻断其他 Agent**（FR-018、US-3 场景 4）。

## 2. `.axion/config.yaml`（全局，Clarification Q2 引入）

```yaml
sandbox:
  http:
    allowed_domains:   # 全局 HTTP 域名白名单；缺省/空列表 = 未配置
      - "*.example.com"
      - "wttr.in"
```

- 文件不存在 = 未配置（合法状态，不报错）
- 有效白名单 = Agent 级声明 ? Agent 级 : 全局（覆盖语义）
- 两级均未配置 → fail-closed：`http_get` 一律拒绝（返回失败 ToolResult，不静默放行）

## 3. 环境变量注入规则

| 规则 | 行为 |
|------|------|
| `${VAR}` 占位 | 加载时用 `System.getenv` 解析 |
| 环境变量缺失 | 报错并**指明具体变量名**（如 `环境变量 DEEPSEEK_API_KEY 未设置，被 provider "deepseek" 引用`） |
| 明文 key | 拒绝加载该配置（安全校验） |

## 4. 白名单域名匹配（Sandbox HTTP 层）

- 提取目标 URL 的 host（含端口归一：按 host:port 全量匹配）
- 支持通配符 `*`：`*.example.com` 匹配 `a.example.com` 与 `a.b.example.com`，不匹配 `example.com` 本身；`*` 单独一条 = 放行所有（允许但不推荐）
- 匹配 → 放行；不匹配 → 拒绝并返回失败 ToolResult（含拒绝原因），Agent 收到后可修正（US-2 场景 6）
