# Contract: Sandbox 三层白名单（enforce 行为契约 + 三配置键）

> 本契约分两部分：① `Sandbox` 接口（002 已交付，本节**不改**，列出为行为基准）；② 三块白名单配置键（本节新增对外概念，跨节契约——25/31 节 Demo 消费）。
> 契约测试承载：`WhitelistSandboxTest` 四组（文件路径/Shell 命令/HTTP 域名/接线回归）+ `CliAgentConfigurationTest` 装配断言 + 工具接线回归。

## ① Sandbox 接口（002 已定死，本节零改动）

```java
public interface Sandbox {
    void enforce(SandboxAction action);   // 表达"在受控环境里执行一个动作"的意图，不表达任何一档实现
}
public record SandboxAction(ActionType type, String target) {}   // target = 路径/命令/URL，由 type 解释
public enum ActionType { FILE_READ, FILE_WRITE, SHELL_COMMAND, HTTP_REQUEST }
public class SandboxViolationException extends RuntimeException {}   // message = 人类可读违规原因
```

行为契约（WhitelistSandbox 兑现）：

| 输入 | 行为 |
|------|------|
| `(FILE_READ \| FILE_WRITE, path)` | 同路由 checkFilePath：`Path.of(path).normalize().toAbsolutePath()` 后对构造期 `normalize().toAbsolutePath()` 化的根做 `startsWith`；不命中 → 抛"路径不在白名单内: {rawPath}"；Windows 下 root/target lower-case 归一后比较（⑦b） |
| `(SHELL_COMMAND, command)` | `command.trim().split("\\s+")[0]` 首 token 精确匹配（大小写敏感）；不命中 → 抛"命令不在白名单内: {firstToken}"；空命令/环境变量前缀按精确匹配拒绝 |
| `(HTTP_REQUEST, url)` | `URI.create(url).getHost()` 取 host；host null → 拒绝；模式精确全等或 `*.` 前缀通配（`host.endsWith(pattern.substring(1))` 带点号边界）；不命中 → 抛"域名不在白名单内: {host}" |

不变式：三个校验方法全部 private；任意校验失败抛 SandboxViolationException → ToolExecutor 既有 catch → `tool_invocations` `success=false` + `error_message`（零新增审计逻辑）。

## ② 三块白名单配置键（本节新增，跨节契约）

```yaml
file:
  allowed_paths:        # 空 = 什么都不允许（fail-closed）；相对根按启动目录解析
    - .axion/workspace
    - /data/reports
shell:
  allowed_commands:     # 首 token 白名单；精确匹配、大小写敏感
    - ls
    - cat
http:
  allowed_domains:      # 精确域名或 *.example.com 通配（点号边界）
    - wttr.in
    - api.example.com
```

- **契约方**：25 定时任务（Demo 一）、31 日报（Demo 三）起，Agent 用到的域名/路径/命令必须列入白名单——自本节起 Demo 的 HTTP 调用从"全放行"变为"真实白名单拦截"
- **不覆盖**（明确不做，跨节声明）：MCP 工具（McpToolAdapter 无 enforce）——挂 MCP server 的 agent 配置须显式声明风险边界
- **稳定性口径**：不可热更新（构造期拷贝）；任一为空构造期 WARN；空 = 全拒绝
- **扩展预留**：换容器/microVM 只新增实现类 + 装配换一行，本契约与配置键继续有效（宪法 VI）
