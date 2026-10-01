# Quickstart 验证指南: 003-cli

本指南是**验证/跑法说明**，不是实现文档。实现在 `tasks.md` 与实现阶段产出。

## 前置条件

1. 分支 `003-cli`，JDK 21，Maven 可用（每次构建前 export JAVA_HOME/PATH，见 002 quickstart）
2. 001/002 已交付并合并（引擎全链就绪）
3. 人工验证需要 `DEEPSEEK_API_KEY`（chat 真对话）；纯结构验证不需要

## 日常验证（默认，秒级）

```bash
mvn test
```

预期：会话层测试全绿——
- `SessionManagerTest`（mock 版）：同三元组幂等（同一实例 + 同 id）、三元组任一不同则不同、id 拼接只此一处的架构断言、save 触发落库、get 反序列化重建
- `SessionRepositoryTest`：手工 schema.sql 建表（坑八）、可存可读、messages_json 回读后消息完整（含 toolCall 嵌套）、模拟重启（新建 context 重查）历史还在
- 坑九架构断言：启动类带 `@EnableJpaRepositories`/`@EntityScan` 且 basePackages 含 `com.axion.storage`

## 完成定义

```bash
mvn clean verify     # 全绿（含静态检查门禁）才算实现完成
```

## 人工验证（做完怎么验，机器判不了的部分）

> 2026-09-02 已实跑通过（用户终端 + 真实 DeepSeek）。以下为实测沉淀的验证流程与核对点。

### 准备（PowerShell，本机）

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot"
$env:PATH = "$env:JAVA_HOME\bin;D:\tools\apache-maven-3.9.16\bin;$env:PATH"
$env:DEEPSEEK_API_KEY = "<有效 key>"   # 无效 key 会 401；可先直测：
# curl.exe -X POST https://api.deepseek.com/v1/chat/completions -H "Content-Type: application/json" -H "Authorization: Bearer $env:DEEPSEEK_API_KEY" -d "{\"model\":\"deepseek-chat\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"
```

### 验证流程（顺序执行，每一步的核对点）

1. **轻命令链**（不启动 Spring，秒回）：
   ```powershell
   java -jar axion-boot/target/axion-boot-0.1.0-SNAPSHOT.jar init        # 跑两遍验幂等
   java -jar axion-boot/target/axion-boot-0.1.0-SNAPSHOT.jar profile list
   java -jar axion-boot/target/axion-boot-0.1.0-SNAPSHOT.jar profile create weather   # 重复 create 提示不覆盖
   java -jar axion-boot/target/axion-boot-0.1.0-SNAPSHOT.jar profile show weather     # api-key 必须显示占位 ${...} 而非明文
   java -jar axion-boot/target/axion-boot-0.1.0-SNAPSHOT.jar provider list             # 同理：api-key 永不输出
   java -jar axion-boot/target/axion-boot-0.1.0-SNAPSHOT.jar status
   java -jar axion-boot/target/axion-boot-0.1.0-SNAPSHOT.jar --help                   # 9 命令组 12 叶命令
   ```
   核对点：全部无 Spring 启动日志；`--help` 用 `sed 's/\x1b\[[0-9;]*m//g'` 剥 ANSI 后核对命令表
2. **chat 多轮对话**：
   ```powershell
   java -jar axion-boot/target/axion-boot-0.1.0-SNAPSHOT.jar chat --profile weather
   > 查一下西安天气并告诉我穿什么     # 核对：中文不乱码（编码跟随终端）
   > 我们刚才聊了什么？               # 核对：模型能复述本轮内容（会话内历史累积）
   > /quit                            # 核对：Spring 上下文干净关闭
   ```
   核对点：启动日志出现 "Found 3 JPA repository interfaces"（坑九实机核验；3 = llm_calls/tool_invocations/sessions）
3. **跨重启恢复**：退出进程后重新 `chat --profile weather`，问"我们之前聊过天气吗？"——
   模型应回答聊过（messages_json 序列化回读）；`session list` 显示该会话概要 + last_active 时间戳
4. **serve/gateway 占位**：启动输出占位提示、不抢 8080（web(NONE) 已保证）
5. 会话幂等、隔离、持久化——已由 harness 覆盖，`mvn test` 绿即打勾（课件 §五）

### 本课实跑踩过的坑（修复后回归要点）

| 坑 | 症状 | 修复 | 回归方式 |
|----|------|------|---------|
| Windows 终端编码 | 中文输入变乱码、模型读不懂 | Scanner 跟随 `console().charset()`（CliChannel） | 人工：chat 中文多轮 |
| 嵌套 fat jar | boot jar 内 cli 类加载失败 | cli 插件 skip、boot 唯一打包入口 | 人工：boot jar 跑任何命令 |
| Tomcat 抢 8080 | chat 启动 PortInUseException | 重命令 `web(NONE)` | 人工：chat 启动无端口日志 |
| 建表脚本无人执行 | no such table: sessions | `spring.sql.init.mode=always` | 人工：删库后 chat 自动建表 |
| jar 被瞬时锁 | Maven rename failed | 等待数秒重试（Defender 扫描锁） | 打包重试 |

## 遗留

- http_get 内置工具归第 20 节——本课 chat 验证以纯对话进行，工具就位后补跑 Demo 一完整版（002 flow-status 遗留项）
