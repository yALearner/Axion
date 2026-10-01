# 人工验收方法论（机器判卷之外的验收）

> 适用时机：S6 implement 完成、收尾 DoD 第 6 条「剩余人工项清单」确认后。
> 首发于 002-react（2026-09-02，Demo 一真模型跑通）；后续各节复用本方法，节级细节写进各节 quickstart.md。

## 原则

1. **能自动化的不留给人工**：凡可 mock 的行为（循环、截断、审计、异常路径）全部由 harness 钉死；
   人工只验"测不出来"的：真模型行为、真实网络、真实落库、代码阅读判断。
2. **真实验证 ≠ 生产代码**：用「临时 harness」跑真装配，**不进交付物**，验收后删除。
3. **如实记录**：验证结果（含失败重跑）写进 flow-status「人工验收待办」；缺 key / 缺工具导致的
   无法验证项如实记为已知缺口，不伪装通过。

## 临时 harness 模板（真装配 + 真落库 + 真模型）

放在 `oryxos-boot/src/test/java/com/oryxos/boot/`（**未提交、验收后删除**），八个要点：

1. `@Tag("integration")`——surefire 默认排除，CI 不受外网影响；跑法 `-Dtest.groups=integration -Dtest.excludedGroups=`
2. `@SpringBootTest(classes = OryxOsApplication.class)` 拿到真实 Spring 上下文 + 真实 JPA Repository；
   **仓储/实体扫描**：主类已声明 `@EnableJpaRepositories/@EntityScan(basePackages="com.oryxos.storage")`（002 fix），无需在测试重复
3. **占位属性覆盖**必须补全整元素字段（只覆盖一个字段会把 YAML 列表项的其余字段顶掉触发启动校验）：
   ```java
   properties = {
     "oryxos.providers[0].name=demo", "oryxos.providers[0].api-key=demo-dummy",
     "oryxos.providers[0].base-url=http://127.0.0.1:9", "oryxos.providers[0].model=demo-model",
     // [1] 同款
   }
   ```
4. **真 key 在测试内手工装配**（001 冒烟同款：`OpenAiApi.builder().apiKey(System.getenv("DEEPSEEK_API_KEY"))...`），
   测试开头 `Assumptions.assumeTrue(key != null && !key.isBlank())`——无 key 时 SKIP 不是 FAIL
5. **手工 schema.sql 落库**（坑八）：`ClassPathResource("schema.sql")` 读脚本、按 `;` 切分逐条 execute——
   测试与生产同一份脚本；`.oryxos` 父目录在 static 块先建（数据源相对路径，连接池启动即连）
6. **真实链路装配**：真实 ProviderService（真 Repository）→ 真实 SessionManager/ProfileRegistry/ContextLoader/
   PromptBuilder/ToolExecutor/ReActLoop/AgentService；临时工具（如 JDK HttpClient 版 http_get）打底
7. **打印完整对话链**（角色/内容/工具请求/工具结果）——人工核对"想→做→看"的实拍证据
8. **审计断言**：`llmCallRepository.count() ≥ 2`（多轮）、`toolInvocationRepository.count() ≥ 1`、
   逐行打印两表记录（session 关联、success、durationMs）

## 标准验证步骤（6 步）

1. **环境**：`export JAVA_HOME/PATH`（本机未配置）；PowerShell 用 `$env:` 且 mvn 参数**一行写完**
   （空值参数必须带引号 `"-Dtest.excludedGroups="`，折行粘贴会解析错误）
2. **跑 harness**：`DEEPSEEK_API_KEY=xxx mvn -pl oryxos-boot -am test -Dtest=XXXManualIT -Dtest.groups=integration -Dtest.excludedGroups= -Dsurefire.failIfNoSpecifiedTests=false`
3. **核对四段证据**：① 最终答复非空可读 ② 对话链含工具请求+结果 ③ 审计核对行数量达标 ④ 断言全绿
4. **落库核对**：审计行 session 关联正确、success=true、durationMs 有值；
   注意 surefire 工作目录 = 模块目录，相对路径数据源落 `<module>/.oryxos/oryxos.db`（生产 java -jar 从仓库根启动落根 `.oryxos/`，行为一致）
5. **code review 证据**：grep 无禁用路径（如 `ChatClient`/`ToolCallingManager`/`executeToolCalls`）、
   核心类行数符合"自实现数十行"预期
6. **清理 + 记录**：删除临时 harness 与演示数据（用户拍板）；flow-status「人工验收待办」逐项勾选，
   无法验证项如实记为待办（如"第 20 节工具就位后补跑"）

## 本机已知坑（踩过一遍，别再踩）

| 坑 | 现象 | 解法 |
|----|------|------|
| PowerShell 折行粘贴 | `>>` 续行接两个引号参数 → ParserError | mvn 参数一行写完 |
| surefire 工作目录 = 模块目录 | 相对路径数据源/文件落 `<module>/.oryxos/` 而非根 | 预期行为；核对时指对路径即可 |
| `scanBasePackages` 不作用于 JPA 扫描 | 真实启动时 Repository/实体 Bean 缺失 | 主类显式 `@EnableJpaRepositories/@EntityScan`（002 fix 已落） |
| 索引式属性覆盖 | 只覆盖 `providers[0].api-key` 会把 name 顶掉 → 启动校验失败 | 覆盖整元素全部字段；**@DynamicPropertySource 同样适用**（008 又踩一次：registry.add("oryxos.providers[0].api-key") 顶掉 name → 上下文启动失败「列表项缺少 name」） |
| 会话级环境变量不可见 | 用户在某个 PowerShell 窗口 `$env:KEY=...` 配的 key，Claude/Maven 进程继承不到 → harness assumption SKIP | 让用户在自己配了 key 的终端跑 mvn；或升为用户级环境变量 `[Environment]::SetEnvironmentVariable("KEY", $env:KEY, "User")`（新开终端生效） |
| 本机无符号链接特权 | `Files.createSymbolicLink` 抛"客户端没有所需的特权" | 测试用 `mklink /J` junction（Java 以 `isOther()` 识别）；ContextLoader.isBinding 已双形态支持 |
| Mockito 混合 matcher 与裸值 | `InvalidUseOfMatchers` | 全用 matcher（裸值包 `eq(...)`） |
| Error Prone `-Werror` | `LocalDateTime.now()`/`split(regex)`/`toLowerCase()` 等触发告警即失败 | 显式时区 / `split(regex, -1)` / `toLowerCase(Locale.ROOT)` / 显式 UTF_8 |
| FindSecBugs CRLF 注入 | 用户可控值进日志参数被拦 | 日志参数不带 sessionId/名称类字段（001 先例），关联信息在审计表 |

## 定时任务钟推特例（008 实录，2026-09-08）

课件 25 §五说「真实到点触发只能真等一次」——harness 把它变成了可等的自动化形态：

1. **等真钟**：cron 配短周期（六段含秒，如 `*/30 * * * * *`——**Spring 6 只接受六段**，五段启动报错），测试内轮询审计表计数：
   `while (llmCallRepository.count() == before && now < deadline) Thread.sleep(2000);`——计数一动 = Spring 的钟真走了
2. **AGENT.md fixture 在 static 块预建**（上下文创建前就位——profileRegistry bean 启动扫描注册）：
   `Files.writeString(Path.of(".oryxos", "agents", "x", "AGENT.md"), "...", UTF_8)`；bootstrap 缺失只 WARN 不炸
3. **真 key 的 provider 覆盖**：@DynamicPropertySource 补全整元素（坑表）+ 测试内 `Assumptions.assumeTrue(key != null)`——无 key SKIP 不 FAIL
4. **证据口径**：`llm_calls` 新增 ≥1 是**硬断言**（钟推必然到 LLM 层，真 key 下完整 ReAct）；`tool_invocations` 数量**打印不硬断**（模型行为不确定）；核对 session 三元组（钟推 = `scheduler|scheduler|profileName`）
5. **日志实拍判据**：触发线程名 `[taskScheduler-N]` 是「钟真的走了」的实拍证据（区别于手调 runOnce 的 main 线程）

## 管理台定时任务页人工核对（010 实录，2026-09-10）

课件 28 节的「能管理」面落在管理台第一个写操作页——机器判不了运营语义，四条实拍口径：

1. **立即执行不等 cron**：cron 配远期（如每天 8 点），演示一律点「立即执行」验证——执行走真 ReAct（日志 `[virtual-N]` 线程 + 第 N 轮 LLM 调用/工具执行行是实拍证据）
2. **停用语义边界**：停用只拦「到点自动触发」，**立即执行不受影响**（spec US2-2 拍板）——管理台停用行有提示文案「停用仅停止到点自动触发，立即执行不受影响」；要人工看到停用生效需等真钟或改短周期 cron
3. **任务级/工具级成败两层语义**：列表「上次完成」= 循环完整跑完（工具失败被 Agent 消化仍算完成）；单次工具成败看审计表 `tool_invocations`——演示前先配好 notify 渠道行，否则「渠道不存在」4ms 快速失败属预期
4. **jar 内前端产物**：`java -jar` 启动时前端静态产物在 jar 里——改前端文案后必须重新 `mvn package` 再重启，改文件不重启不生效；Windows 控制台中文乱码 = 代码页问题，`chcp 65001` 纯显示修复

## 与流程的关系

- 收尾 DoD 第 6 条（验收报告）→ 本文件
- 节级「人工部分」写在需求文档「验收标准」，节级跑法细节写 quickstart.md，本文件是通用模板
- 结果回写 flow-status「人工验收待办」+ PR 正文「人工验收」段
