# 路线图

| 阶段 | 形态 | 重点 | 状态 |
|------|------|------|------|
| **阶段一** | 单机私有部署 | 完整运行时内核（五大核心能力），把单机做扎实 | 🚧 开发中 |
| **阶段二** | 底座分布式部署 | 多实例 + 外置状态，高可用，水平扩展 | 📋 规划中 |
| **阶段三** | 分布式 Agent 协作 | 跨节点/跨组织 Agent 互发现、互委托 | 💡 远期愿景 |

## 阶段一 — 核心运行时（当前）

**第一周**：Provider 抽象 + ReAct Loop
- `axion-core`、`axion-provider`、`axion-channel-cli`、`axion-cli`
- Demo：`axion chat` 多轮对话，Agent 调 HTTP Tool

**第二周**：Memory + Tool 体系
- `axion-memory`、`axion-tool`
- Demo：Agent 跨对话记偏好；调本地文件和外部 MCP server

**第三周**：Web Service
- `axion-web`、`axion-storage`
- Demo：10 个 REST 端点完整调用

**第四周**：多 Agent 演示 + 工程化收尾
- 所有模块
- Demo：多 Agent 并存；Session 跨重启恢复；定时任务到点触发；项目主页可访问

## 阶段二 — 治理层（规划中）

- 多租户 RBAC
- SSO（OIDC / SAML）
- 完整审计与 SIEM 导出
- Web 管理控制台
- 向量检索（pgvector）
- 容器级沙箱（gVisor / Firecracker）
- 集群化部署（Nacos / Sentinel / SkyWalking）

## 阶段三 — 分布式 Agent（远期愿景）

- 跨节点 Agent 发现
- Agent 间相互委托
- 联邦协议
