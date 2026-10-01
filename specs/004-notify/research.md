# Research: 004-notify（技术选型与裁决记录）

> 本 feature 无未决 NEEDS CLARIFICATION——全部裁决已在需求文档以 6 条拍板结论锁定（2026-09-03 / 2026-09-04），本文件记录裁决内容与备选，作为 plan/tasks 的依据。

## 裁决 1：渠道存储形态——SQLite 全局注册表（拍板 2026-09-03）

- **Decision**: `notify_channels` 存 SQLite 全局注册表（`name`/`type`/`url`/`description`），`AGENT.md` frontmatter 不含 `notify_channels` 字段；Agent 正文按名引用渠道
- **Rationale**: 技术方案 §6.8/§493 口径（"notify_channels 不属于 Profile 或 frontmatter；由 SQLite 全局注册表管理"）；课件第 19 节旧版（frontmatter 字段）与技术方案冲突，且课程第 31 节已拍板过同一方向——技术方案是拍板后的新版，002-react"冲突一律参照课件"拍板不适用于本次。换渠道只改注册表一行，Agent 零改动（场景二）
- **Alternatives considered**: 课件版 frontmatter `notify_channels` 字段（否决：webhook 地址进 Agent 配置、换渠道要改 Agent）；环境变量/配置键（否决：不是"注册表"语义、无按名引用）

## 裁决 2：adapter 选择职责——NotifyTools 持显式映射，Registry 纯数据（拍板 2026-09-04）

- **Decision**: `NotifyChannelRegistry` 只做"按名解析出 NotifyTarget"的纯数据活；adapter 按 `target.channelType()` 从装配处显式 `Map<channelType, NotifyChannelAdapter>` 选择，选择逻辑在 `NotifyTools`
- **Rationale**: 与技术方案 §6.8 字面"注册表解析适配器和 URL"不完全一致，按拍板执行——跨节契约越小越稳（后续节只见 NotifyTarget 与显式 Map，不依赖注册表内部结构）；显式映射呼应宪法 III 哲学（不靠容器扫描）
- **Alternatives considered**: 技术方案字面版（注册表直接解析 adapter → 注册表与实现类耦合，换实现要动数据层，否决）；容器扫描 Bean（违反宪法 III 哲学，否决）

## 裁决 3：`channel` 缺省口径——恰好一条才允许缺省（拍板 2026-09-04）

- **Decision**: 注册表恰好一条渠道才允许缺省取它；多条或为空时缺省 → 明确报错要求显式指定 channel
- **Rationale**: 推错群是不可见的错误，把出错窗口压到最小；课件"取第一个渠道"口径被否决（多条时取第一个可能推到错群且不报错）
- **Alternatives considered**: 课件版"取第一个"（否决：静默推错群）；一律必填（否决：单渠道场景徒增 LLM 负担，且与 FR-6 schema"channel 可选"矛盾）

## 裁决 4：HTTP 客户端——RestClient + 显式 timeout（机械选型）

- **Decision**: `WebhookNotifyAdapter` 构造注入 `RestClient`（课件签名逐字），装配处用 Boot 自动配置的 `RestClient.Builder` 构建，必设 connect/read timeout（建议 connect 3s / read 10s，实现时定稿）
- **Rationale**: Spring Boot 3.5 自带（spring-web），同步阻塞符合宪法 VII；timeout 防慢 webhook 拖死 ReAct 轮次（需求文档实现级明确）
- **Alternatives considered**: java.net.http.HttpClient（否决：失去 Boot 装配与全栈一致性）；RestTemplate（否决：维护模式）

## 裁决 5：测试分层——MockWebServer 假 webhook，真 webhook 归人工（课件 §四明示）

- **Decision**: `WebhookNotifyAdapterTest` 用 MockWebServer 本地起假 webhook（断言 body 带 content、URL 来自 config 不硬编码、5xx 异常上抛）；真 webhook 验证挪人工部分（用户提供真实群机器人地址）。**2026-09-05 H3 实测修正**：Boot 3.5 BOM 不管理 okhttp3 → mockwebserver 用显式版本 4.12.0（与本机已落库 okhttp 4.12.0 对齐；需求文档原"Boot BOM 管理版本"表述已同步修正）
- **Rationale**: 课件明示 MockWebServer"不算外网依赖，仍是单测层"；假 webhook 测协议、真 webhook 验配置，两者分离保证 `mvn test` 全绿不依赖外网
- **Alternatives considered**: 直接打真 webhook（否决：测试依赖外网/密钥，违反全绿可重复性）

## 裁决 6：axion-tool pom 结构性新增（超出需求文档 pom 白名单字面，G2 提交用户确认）

- **Decision**: 需求文档交付清单写"pom 增加 spring-web + mockwebserver"，实测 axion-tool pom 现状（仅 core + spring-ai-model）后确认还需两项：① `axion-storage`（compile）——`NotifyChannelRegistry` 必须消费 `NotifyChannelRepository`，能力层依赖 storage 符合 CLAUDE.md 依赖方向；② `spring-boot-starter-test`（test）——模块无任何测试依赖，首个测试需 JUnit 5 + Mockito 栈（axion-core 同款先例）
- **Rationale**: 均为实现 FR-5/测试清单的机械必需结构件（类比 003 的 4 个父命令类先例——补列需求文档交付清单）；不构成新模块、不改依赖方向
- **Alternatives considered**: Registry 不依赖 Repository 改注入数据接口（否决：多一层无收益抽象，违反"不建需求文档之外的抽象层"）；测试手写无框架（否决：违反全仓测试栈一致性）

## 裁决 7：装配时机——RestClient bean 与 adapter 显式 Map 归第 20 节装配处

- **Decision**: 本节交付构造注入形态（`WebhookNotifyAdapter(RestClient)`、`NotifyTools(Sandbox, Map, Registry)`）+ mock 单测；生产 bean 装配（RestClient.Builder 构建、`Map.of("webhook", webhookAdapter)`）与工具集注册同期在装配处落（第 20 节，003 FR-10 口径）
- **Rationale**: 需求文档交付清单无装配类（FR-7 明说"装配处"，但交付物列不含它）；工具注册归第 20 节是既定跨节契约，装配随之同点落地，本节不新增交付清单之外的类
- **Alternatives considered**: 本节即建装配类（否决：工具 Map 尚未接线，装配类成为半接线死代码，且超白名单）

## 裁决 8：包结构（需求文档实现级明确）

- **Decision**: `com.axion.tool.notify`（接口 + NotifyTarget + WebhookNotifyAdapter + NotifyChannelRegistry）+ `com.axion.tool.builtin`（NotifyTools）；`com.axion.storage`（NotifyChannelEntity + NotifyChannelRepository，flat 包沿用 storage 现状）
- **Rationale**: 需求文档实现级明确（课件 `io.axion` 机械翻译 `com.axion`）；builtin 子包为后续第 20 节 FileTools/ShellTools/HttpTools 落位预留同款结构
- **Alternatives considered**: 全部 flat 到 `com.axion.tool`（否决：需求文档已定子包，且与后续内置 Tool 群混放不清）
