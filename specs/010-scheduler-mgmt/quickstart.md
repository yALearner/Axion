# Quickstart: 010-scheduler-mgmt 验证指南

> 机器可判的部分跑什么命令、预期什么结果；人工可验的部分怎么做。实现细节见 tasks.md，本文件只做验证入口。

## 前置

- 环境变量：`JAVA_HOME`（JDK 21）+ `PATH`（`export JAVA_HOME=... && export PATH=$JAVA_HOME/bin:$PATH`，本机 002 起惯例）
- 项目根 `D:\myproject\Axion`；工作区未初始化先 `axion init`
- **gate 内无 key** 的自动化全跑（mock provider 路径）；**真 key** 的 IT 手动跑（`DEEPSEEK_API_KEY` 注入）

## 一、自动化（机器判卷）

### 1. 全量门禁（收尾 DoD 证据）

```bash
mvn clean verify
```

预期：全绿（不写死用例数）；P3C/SpotBugs/FindSecBugs/PMD 静态检查门禁一并绿。

### 2. 本 feature 核心测试（按模块）

```bash
# 最值钱回归 + ⑦a 并发回归 + ⑦c id 缺失回归（008 测试类增补）
mvn -pl axion-core test -Dtest=AgentSchedulerTest

# 存储层：两表真实存在（PRAGMA 核对列）、可存可读、约束生效
mvn -pl axion-storage test -Dtest=ScheduledTaskRepositoryTest

# 四端点契约（standalone MockMvc：双信封 + 404 + 504 + 返回体形状）
mvn -pl axion-web test -Dtest=ScheduleApiControllerTest

# E2E 五步（mock provider、无 key、临时 SQLite）：
# 启动即登记 → GET /schedules → POST run 走真 ReAct → 落库断言 → PUT 停用 → 停用不记历史
mvn -pl axion-boot test -Dtest=ScheduledTaskE2ETest
```

预期关键断言（对号）：

- [ ] 登记后 run_count=0、enabled=true、next_run_at 非空
- [ ] runNow 后 run_count=1、last_status=success、executions 恰一条 success=true
- [ ] 停用后到点：verify process **never** + recordExecution **never**（最值钱回归）
- [ ] runNow 与到点同时到达同任务：executeInternal 串行不双跑（⑦a）
- [ ] id 缺失 → 启动报错；id 冲突 → 报错指明 Profile

### 3. 依赖方向 grep（DoD 不变量辅助）

```bash
# core 不得依赖 storage（契约在 core、实现在 storage）
grep -r "com.axion.storage" axion-core/src/main --include="*.java" | wc -l   # 预期 0
```

## 二、人工项（真 key / 实机）

### 4. SchedulerFlowIT（@Tag integration，真 key 链路对账）

```bash
DEEPSEEK_API_KEY=xxx mvn -pl axion-boot test -Dtest=SchedulerFlowIT
```

对账点（需求文档场景二，不多不少）：scheduler 会话复用（连续触发两次仍一条）/ llm_calls 恰 2 条 / tool_invocations 恰 2 条（http_get + notify）全成功 / webhook 真收到消息体。

### 5. RestartRecoveryIT（@Tag integration，重启四样恢复）

```bash
DEEPSEEK_API_KEY=xxx mvn -pl axion-boot test -Dtest=RestartRecoveryIT
```

对账点：kill 后重新 serve → GET /sessions/{id} 完整历史 / GET /memory 核心记忆 / GET /schedules 状态与历史（run_count/上次结果/下次触发）/ llm_calls 跨重启不断档。

### 6. 管理台「定时任务」页人工核对

1. `mvn package`（含前端构建）→ `axion serve` → 打开 `http://localhost:8080/admin/schedules`
2. 列表渲染任务与状态（Profile/cron/下次触发/上次结果/次数/启用与否）
3. 点「立即执行」→ 同步等待返回 → 显示成功/失败 + 耗时
4. 点「停用」→ 列表刷新显示已停用；再点「启用」恢复
5. **其余五页仍只读**（无任何写按钮，⑦d 例外只开定时任务页）

### 7. Demo 前置六项打勾（FR-7）

- [ ] `application.yaml` `http.allowed_domains` 含天气源 `api.open-meteo.com` + webhook 域名（飞书 `*.feishu.cn` 已启用 / 企微 `qyapi.weixin.qq.com` 注释待启用）；file/shell 全拒（010 已落：两节配置为 `[]`）
- [ ] notify_channels 配好：手动 notify 一次，群真收得到
  - H3 核实（T003）：本机无 sqlite3 CLI，人工写入通道 = Python sqlite3 模块：
    ```bash
    python -c "import sqlite3; c = sqlite3.connect('.axion/axion.db');
    c.execute(\"INSERT INTO notify_channels (name, type, url, description) VALUES ('team-lark','webhook','https://open.feishu.cn/open-apis/bot/v2/hook/<真实地址>','团队群机器人')\");
    c.commit(); c.close()"
    ```
- [ ] 测试 Profile schedules 含 id + cron + 显式时区（Asia/Shanghai）——`.axion/agents/weather-demo/AGENT.md`：
  ```markdown
  ---
  name: weather-demo
  provider:
    name: deepseek
  tools: [http_get, notify]
  schedules:
    - id: weather-8am
      cron: "0 0 8 * * *"
      zone: Asia/Shanghai
      message: 查北京天气推送穿搭建议
  ---
  你负责每日天气推送……
  ```
- [ ] serve 单 key 正常起（OpenAi 自动配置排除仍生效——application.yaml 已排除，010 实测 E2E 无 key 起上下文）
- [ ] MCP tool list 可查（如配）
- [ ] MEMORY.md 有偏好可读（E2E 已实测 save_memory 写入链路）

### 8. 多 Agent 三边界（spec US5 人工复验）

两个差异 Profile（A 只文件工具、B 只 HTTP 工具）同实例：工具隔离（A 对话拿不到 B 独有工具）/ 会话隔离（各自会话不串）/ 定时隔离（A 定时异常 B 下个触发点照常）。

## 三、验收报告口径

- 机器已判卷：第 1~3 节（全量 verify + 各模块测试 + 依赖方向）
- 等人人工过：第 4~8 节（两个 IT 真 key + 管理台页 + Demo 六项 + 多 Agent 三边界）
- 执行方法细则：`.claude/skills/axion-spec/references/manual-acceptance.md`
