# 接口契约：Sandbox（接口墙）

> **跨节契约**：第 20 节起各涉外工具在 `execute` 首行接入；第 23/24 节交付 `WhitelistSandbox` 实现与三层白名单。接口不随实现升级变化——扩展阶段换容器/microVM 只新增实现类。修改本契约视为修改公共接口，必须停下报告。

## 接口形态

```java
Sandbox.enforce(SandboxAction action)

SandboxAction = { type: ActionType, target: String }
ActionType     = FILE_READ | FILE_WRITE | SHELL_COMMAND | HTTP_REQUEST
```

| 元素 | 说明 |
|------|------|
| `enforce` | 唯一方法，表达"在受控环境里执行一个动作"的意图；校验失败抛 `SandboxViolationException` |
| `ActionType` | 四值：文件读 / 文件写 / Shell 命令 / HTTP 请求（读写分开便于未来按读/写分权限） |
| `target` | 动作目标（路径 / 命令 / URL） |

## 行为不变量

1. **接口中立**：签名不出现"白名单/容器镜像/VM 配置"字样（技术方案 §6.7——用最重的 microVM 实现反向套此签名也应能干净套入）
2. **本节状态**：纯接口墙，零实现、无白名单配置、无人调用（FR-7）
3. **接线约定**：涉外 IO 的 `enforce` 由各工具在 `execute` 首行自行调用（技术方案 §6.7 原文），**ToolExecutor 不持 Sandbox 引用**（core 不反向依赖 axion-tool）
4. **审计**：Sandbox 违规不新增审计逻辑——异常复用 `ToolExecutor` 既有失败审计路径（`success=false` + `error_message`，技术方案 §6.7）
5. **演进**：`WhitelistSandbox`（第 23/24 节）→ 容器隔离 → microVM，接口不变

## 消费方

- 当前：无（接口先立）
- 后续：第 20 节 `FileTools`/`ShellTools`/`HttpTools`（execute 首行）、第 19/20 节 `NotifyTools`（HTTP_REQUEST 校验）、第 23/24 节 `WhitelistSandbox` 实现
