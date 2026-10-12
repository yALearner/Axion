# Quickstart: 012-agent-mgmt 验证指南

> Phase 1 输出。机器判卷部分（harness 五类 + 改造点回归）全绿即过；人工部分按 manual-acceptance.md 的「临时 harness」模式执行（真装配 + 真落库 + 真模型，验收后清理）。

## 前置

- Java 21 + Maven；实施环境 export 后构建（JAVA_HOME/PATH，002 先例）
- 人工项需：`DEEPSEEK_API_KEY`（真模型链路）、`OPS_WEBHOOK_URL`（推送核对）；本机 Node（管理台 npm run build）
- 已建分支 `012-agent-mgmt`；`mvn clean verify` 前先 `mvn clean`（010 实录：IDE 污染 target/）

## 机器判卷（harness）

```bash
# 全量门禁（10 模块；含 Spotless/P3C/SpotBugs/FindSecBugs/PMD——DoD 1）
mvn clean verify

# 本 feature 单测（web 四类 + core 编排/监听类）
mvn -pl axion-web -am test -Dtest='AgentApiControllerTest,WorkspaceApiControllerTest,GenerateTest,AgentLifecycleServiceTest,WorkspaceWatcherTest'

# 改造点回归（DoD 4：跨 feature 契约证据）
mvn -pl axion-core,axion-memory -am test -Dtest='ToolExecutorTest,MemoryToolsTest,MemoryServiceTest,MarkdownMemoryStoreTest,LongTermMemoryStoreTest'
```

预期：坑十~十八逐一对号（删除顺序 InOrder/创建回滚/穿越 400/围栏剥离/503 不发 model=null/ThreadLocal finally 清除/三条录入同一段代码/name 冲突第一步拒/macOS 显式重注册）；006 契约测试零改动回归；009 全局记忆端点退役后 MemoryApiController 调整绿；前序 001~011 测试零回退。

## 人工项（做完怎么验）

### 1. 建 Agent 即上线（真链路）

```bash
axion init 已就绪（.axion 工作区存在）；axion serve
curl -X POST http://localhost:8080/api/v1/agents -H "Content-Type: application/json" \
  -d '{"name":"demo-agent","description":"每天早上九点查北京天气，把穿搭建议发到团队群"}'
curl http://localhost:8080/api/v1/agents          # 立刻出现 demo-agent，全程不重启
```

### 2. 丢目录也即上线

把 `.axion/agents/weather-daily/`（AGENT.md 手写）直接拷入（不走 API）→ 几秒内 GET /api/v1/agents 出现；删除该目录 → 几秒内消失、定时注销。

### 3. 一句话重生成 + 保存生效

`POST /api/v1/agents/demo-agent/generate-files {"description":"..."}`（真 key）→ 返回可解析 AGENT.md 草稿、磁盘无变化 → 改草稿后 `POST /api/v1/agents/demo-agent/files` → 下一轮触发用新内容。

### 4. 目录落对地方 + 删除可追溯

建 Agent 后 `.axion/agents/<name>/` 有 AGENT.md + scripts//skills//REFERENCE.md 脚手架；`DELETE /api/v1/agents/demo-agent` 后目录在 `.axion/archive/`，`axion.db` 里历史 llm_calls/tool_invocations 仍查得到。

### 5. 文件浏览器

管理台「文件」tab 钻进 Agent 目录看 AGENT.md/脚本内容；`curl "http://localhost:8080/api/v1/workspace/file?path=../../etc/passwd"` → 400 挡住。

### 6. 管理台全流程（页面走查）

界面走一遍「新建 Agent → 详情 5 tab 各看一眼 → 生成 → 保存 → 会话里发消息看它跑 → 记忆 tab 看 per-agent 落点 → 编辑 → 删除」；每页空数据/加载中/出错三态有明确反馈（无白屏）。

### 7. 行为一致

API 上传、手工丢目录、启动扫描三种录入对同一目录的校验与报错一致（harness 钉死 + 人工各验一遍）。

### 8. 真模型到点自跑（真 key）

给 demo-agent 设每分钟 cron → 到点真自己跑、webhook 收到；`tool_invocations`/`llm_calls` 逐笔核对（成功都落账）。

## 验收清单（课件 §九逐条）

| 项 | 判卷方式 |
|----|---------|
| 建 Agent 即上线（免重启、立即可见、到点自跑） | 人工 1/8（真 key） |
| 丢目录也即上线（watcher 实时拾取） | 人工 2 + harness WorkspaceWatcherTest |
| 一句话重生成（可解析草稿、不落盘、预览可改） | 人工 3 + harness GenerateTest |
| 目录落对地方（脚手架四件 + 格式与手写一致） | 人工 4 |
| 文件浏览器（钻进目录看文件、越界 400） | 人工 5 + harness WorkspaceApiControllerTest |
| 删除可追溯（archive + 审计可查） | 人工 4 + harness AgentLifecycleServiceTest |
| 行为一致（三条录入同一段代码） | harness + 人工 7 |
| 管理平台全流程（详情 5 tab + 三态） | 人工 6 |
| 不回退（mvn clean verify 全绿） | 机器判卷 |
