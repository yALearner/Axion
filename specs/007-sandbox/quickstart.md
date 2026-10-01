# Quickstart 验证指南: 007-sandbox

> 验证分两层：机器判卷（harness 全绿）+ 人工项（集成验证真实链路）。本节自身无独立 Demo——验收以 harness 全绿 + ls 实跑为准（需求文档「跑通标准」）。

## 前置

- Java 21 + Maven；构建前 JAVA_HOME/PATH export（002-react-prerequisites 先例）
- 前序交付物就位（实测 2026-09-06）：接口墙四件 + 七工具 enforce 接线在 `axion-tool`，`CliAgentConfiguration.sandbox()` 返回 PermissiveSandbox

## 机器判卷：harness 全绿

```bash
mvn test                                    # 日常全跑（WhitelistSandboxTest 四组 + CliAgentConfigurationTest 增补 + 工具接线回归）
mvn clean verify                            # 收尾全量门禁（全绿，不写死用例数）
```

关键回归点对号（需求文档验收标准 harness 表）：

| 测试组 | 关键回归 |
|--------|---------|
| 文件路径组 | 白名单内放行 / 白名单外拒绝 / `../` 穿越被拦（normalize 回归）/ **相对根 + 绝对 target 放行**（⑦a 根未绝对化回归钉）/ 白名单根构造期 normalize+toAbsolutePath / Windows 大小写变体放行（平台条件测试，⑦b） |
| Shell 命令组 | `ls` 放行 / `rm` 拒绝 / `"  ls -la"` trim 容忍 / `LS` 拒绝（大小写敏感）/ `VAR=x ls` 拒绝（⑦d）/ 空命令拒绝 |
| HTTP 域名组 | `wttr.in` 精确放行 / 白名单外拒绝 / `*.example.com` 命中 `api.example.com` 不命中 `evil-example.com`（点号边界）/ host 解析不到拒绝 |
| 接线回归组 | 空配置 = 全拒绝（+构造期 WARN）/ 枚举四值全覆盖（FILE_READ/FILE_WRITE 同路由） |
| 工具接线回归 | FileTools/ShellTools/HttpTools/NotifyTools 各一条：白名单外输入被拦且真 IO 没发生（`verify(executor, never())`） |
| CliAgentConfigurationTest | sandbox bean 为 WhitelistSandbox 实例；PermissiveSandbox 类不存在 |

最值钱回归（课件 24 §四原文）：`../` 穿越 + `evil-example.com`——实现之前写，直接决定 matchesDomain 写法。

## 人工项（做完怎么验）

1. **集成验证（真实链路）**：application.yaml 配 `shell.allowed_commands: [ls]`，`axion chat` 让 Agent 跑白名单外命令（如 `rm`）——确认链路上抛 `SandboxViolationException`、`tool_invocations` 有 `success=false` 记录、`error_message` 人能读懂（课件 24 §五）
2. **接口中立性自查（思维练习）**：`Sandbox.enforce(SandboxAction)` 签名换成 `KataMicroVmSandbox` 实现需要加方法吗？不需要才算墙立住了（1 分钟练习）
3. **配置边界文档化核对**：白名单为空 = "什么都不允许"而非"不校验"，与 FR-002 一致
4. **回归**：改造后七工具原有测试全绿（含 005 的 AxionToolContractTest/ToolRegistryTest）
5. **上层零改动目检**：`git diff` 确认七工具类、`Sandbox` 接口、`ToolExecutor` 零改动（SC-004）
