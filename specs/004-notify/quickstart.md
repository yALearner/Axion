# Quickstart: 004-notify 验证指南

> 运行前提（本机环境）：构建前 export JAVA_HOME 与 PATH（见 002 先例口径）。
> 自动化验证全绿 = 机器判卷部分完成；人工部分（真 webhook）见文末清单。

## 自动化验证（harness）

```bash
# 全量门禁（含 001/002/003 回归 + 静态检查门禁）——收尾 DoD 的判定依据
mvn clean verify

# 只跑本 feature 两模块（日常迭代）
mvn test -pl axion-tool,axion-storage -am
```

预期结果：`axion-tool` 新增 3 个测试类 + `axion-storage` 新增 1 个测试类全绿，前序全部测试回归绿。

| 测试类 | 验证点 |
|--------|--------|
| `WebhookNotifyAdapterTest` | 假 webhook 收到 POST（body 为通用 text 格式 msgtype/text/content——40008 实锤修正）；URL 来自 NotifyTarget.config 不硬编码；5xx 异常上抛不吞（坑十一） |
| `NotifyToolsTest` | 渠道未配置/多条缺省/未知 channelType → 明确报错；唯一渠道缺省可取；**InOrder：enforce 先于 send**（坑十） |
| `NotifyChannelRegistryTest` | 按名命中/未命中；缺省三态（唯一取它 / 多条报错 / 空报错） |
| `NotifyChannelRepositoryTest` | 手工 schema.sql 建表；存读；name 主键唯一；description 可空（坑八） |

## 人工验证（机器判不了的部分；方法论见 `references/manual-acceptance.md`）

> 原则：能自动化的不留给人工；真实验证用临时 harness、**不进交付物**、验收后删除；无法验证项如实记为待办。
> 结果回写 `specs/004-notify/flow-status.md`「人工验收待办」。

### 前置准备

1. `export JAVA_HOME` / `PATH`（本机未配置；PowerShell 用 `$env:`，mvn 参数**一行写完**勿折行）
2. 准备真实群机器人 webhook URL（飞书/企业微信/钉钉均可）→ 放环境变量 `WEBHOOK_URL`，**不进任何提交文件**

### 步骤一：真 webhook 单点验证（验 FR-3 + 配置正确性）

临时 main 或 jshell（验收后删除）：

```java
var adapter = new WebhookNotifyAdapter(RestClient.builder().build());
var target = new NotifyTarget("webhook",
    Map.of("url", System.getenv("WEBHOOK_URL"), "name", "team-lark"));
adapter.send(target, "人工验证消息：004-notify");
```

**预期**：群里收到消息。**errcode 陷阱（2026-09-05 已实锤过一次）**：企业微信业务失败时 HTTP 层仍 200——第一次验证即踩中 `errcode: 40008 invalid message type`（课件骨架 body 格式无效），已按方案 A 修为通用 text 格式；若再遇到"HTTP 200 但群里没收到"，先 curl 该地址读 body errcode（`curl -s -X POST -H 'Content-Type: application/json' -d '{"msgtype":"text","text":{"content":"诊断"}}' <地址>`），把 errcode 记入已知留白。另外：**curl 命令行中文会因 Windows 控制台编码变乱码**，中文验证一律走 harness（Java 发送 UTF-8）。

### 步骤二：临时 harness 全链路验证（真实落库 + 真实审计路径）

放 `axion-boot/src/test/java/com/axion/boot/NotifyManualIT.java`（`@Tag("integration")`；`WEBHOOK_URL` 缺失时 `Assumptions.assumeTrue` SKIP 不 FAIL；**未提交、验收后删除**）。装配：真实 `NotifyChannelRepository`（boot 上下文）+ 真实 `ToolExecutor`（工具 Map 手工塞 `Map.of("notify", notifyTools)`）+ harness 内放行 stub `Sandbox`——不走第 20 节注册也能验到**真实审计落账**。四个用例：

1. **成功路径**：`repository.save(team-lark 行)` → ToolExecutor 执行 `notify(content="测试", channel="team-lark")` → 群里收到 + `tool_invocations` 落 `success=true`、`result_json="已推送到 team-lark"`（带渠道名，不裸记"已推送"）
2. **反例一**：`notify(channel="no-such-channel")` → 明确报错、`tool_invocations` 落 `success=false` + error_message（不静默）
3. **反例二**：注册表两条渠道后 `notify(content="x")` 不传 channel → 明确报错（拍板口径）
4. **JPA 映射回读断言**（覆盖缺口补验）：`repository.findById("team-lark")` 回读实体字段与保存值一致——`NotifyChannelEntity` 的 JPA 映射是单测盲区（storage 测试纯 JDBC、tool 测试 mock repository），harness 里必须补这一刀

跑法（PowerShell 一行写完）：

```bash
mvn -pl axion-boot -am test -Dtest=NotifyManualIT -Dtest.groups=integration -Dtest.excludedGroups= -Dsurefire.failIfNoSpecifiedTests=false
```

### 步骤三：落库核对

1. `PRAGMA table_info(notify_channels)` 四列齐（`name` PK / `type` / `url` / `description`）+ 插入数据可见
2. `tool_invocations` 里 notify 行：`result_json` 带渠道名、`success` / `duration_ms` / `created_at` 有值
3. **已知坑**：surefire 工作目录 = 模块目录 → 数据落 `axion-boot/.axion/axion.db`（不是仓库根，指对路径即可）

### 步骤四：接口中立性自查（思维练习，测不出来）

换成企业微信官方 SDK 的实现，`NotifyChannelAdapter.send(NotifyTarget, String)` 签名需要改吗？答案应该是不需要。

### 收尾

1. 删除临时 harness（未提交的验证代码不进交付物）
2. 结果（含失败重跑、errcode 留白记录）回写 `flow-status.md`「人工验收待办」逐项勾选
3. 无法验证项如实记为待办：**"LLM 在对话里自动调 notify"端到端版 → 第 20 节工具注册后补验**（需求文档既定契约）

## 本节不做（验证时不要误判为缺陷）

- 渠道 CRUD REST 端点 / Web 管理页（归 Web Service 节）
- "LLM 在对话里自动调 notify"端到端版（20 节工具注册后补验）
- WhitelistSandbox 三层白名单实现（23/24 节；本节 Sandbox 为 mock）
- 专用渠道 Adapter / errcode 解析 / 4xx-5xx 重试细分 / 消息长度截断
