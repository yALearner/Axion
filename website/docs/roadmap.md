# Roadmap

| Phase | Form | Focus | Status |
|-------|------|-------|--------|
| **Phase 1** | Single-node private deployment | Complete runtime kernel (5 core capabilities). Solid single-node foundation. | 🚧 In development |
| **Phase 2** | Distributed deployment | Multi-instance + external state. High availability, horizontal scaling. | 📋 Planned |
| **Phase 3** | Distributed Agent collaboration | Cross-node / cross-org Agent mutual discovery and delegation. | 💡 Vision |

## Phase 1 — Core Runtime (Current)

**Week 1**: Provider abstraction + ReAct Loop
- `axion-core`, `axion-provider`, `axion-channel-cli`, `axion-cli`
- Demo: `axion chat` multi-turn conversation, Agent calling HTTP Tool

**Week 2**: Memory + Tool system
- `axion-memory`, `axion-tool`
- Demo: Agent remembers preferences across sessions; calls local files and external MCP servers

**Week 3**: Web Service
- `axion-web`, `axion-storage`
- Demo: 10 REST endpoints fully functional

**Week 4**: Multi-Agent demo + engineering polish
- All modules
- Demo: Multiple Agents coexist; sessions survive restarts; cron tasks trigger on schedule; project site accessible

## Phase 2 — Governance (Planned)

- Multi-tenant RBAC
- SSO (OIDC / SAML)
- Full audit trail with SIEM export
- Web admin console
- Vector search (pgvector)
- Container-level sandbox (gVisor / Firecracker)
- Cluster deployment (Nacos / Sentinel / SkyWalking)

## Phase 3 — Distributed Agents (Vision)

- Cross-node Agent discovery
- Agent-to-Agent delegation
- Federation protocol
