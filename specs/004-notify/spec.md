# Feature Specification: Notify 出站推送模块

**Feature Branch**: `004-notify`

**Created**: 2026-09-05

**Status**: Draft

**Input**: 需求文档 docs/requirements/004-notify.md（课件第 19 节：Notify 模块——出站推送：notify_channels 全局注册表 + NotifyChannelAdapter 接口 + WebhookNotifyAdapter + NotifyTools）

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 配置好的群里收到 Agent 推的消息（Priority: P1）

用户给底座配好一个通知渠道（写入全局注册表：`team-lark` → 团队群 webhook），在对话里对 Agent 说"把'测试消息'推送一下"——Agent 调 `notify(content="测试消息")`，团队群里收到这条消息。入站有 Channel Adapter 负责"消息怎么进来"，本节补的是出站的对称物："消息怎么出去"——一旦触发源变成"到点自动"（定时查天气、汇总日报），请求-响应链路断了，Agent 必须主动把结果送到人能看到的地方（企业 IM 群）。

**Why this priority**: 本节存在的意义（课件 §一/§五）；没有它，到点跑完一整套 ReAct 循环的结果只能烂在 Session 里没人看到。验收锚点是 notify 工具自身链路：注册表解析 → 白名单校验 → 真实 webhook 收到消息。统一掉"往外推一条消息"这件最常见的事，避免每个业务方在 Skill 里手写 webhook 调用。

**Independent Test**: 全 mock 单测：假 webhook（本地 MockWebServer）验证协议正确性（body 带 content、URL 来自配置不硬编码）；mock 注册表/Sandbox/adapter Map 验证四步执行链路；人工部分用真实群 webhook 验证配置正确性（群里收到消息）。不依赖第 20 节工具注册（直接调 `NotifyTools.execute` 即可验证），也不依赖 WhitelistSandbox 实现（mock 承接）。

**Acceptance Scenarios**:

1. **Given** 注册表里有一条渠道 `team-lark`（type=webhook、url=真实 webhook），**When** 调 `notify(content="测试消息")`，**Then** 团队群里收到内容为"测试消息"的消息，审计记录带渠道名（"已推送到 team-lark"）
2. **Given** 注册表里恰好一条渠道，**When** 调 `notify(content="x")` 不传 channel，**Then** 缺省取这条唯一渠道并成功推送（拍板口径）
3. **Given** 任意工具执行，**When** notify 执行成功，**Then** 审计表落一条 success=true、result 带渠道名；执行失败落 success=false、error 明确

---

### User Story 2 - 换渠道不碰 Agent（Priority: P2）

运营要把推送目标从测试群换成生产群：只需在全局注册表里改一行 URL，Agent 按名引用渠道的写法一个字不动；webhook 地址不进对话、不进 Agent 配置（frontmatter 无 notify_channels 字段）。

**Why this priority**: 全局注册表是"配置与 Agent 解耦"的落点（技术方案 §6.8"增加或修改渠道也无需改 Agent"）；渠道 CRUD REST 端点归 Web Service 节，本节先把表、仓储、解析服务的口径交付掉，后面 CRUD 直接消费同一份口径。

**Independent Test**: 仓储级单测（手工建表脚本建表、存读、主键唯一约束、description 可空）；注册表解析单测（按名解析命中/未命中、缺省口径三态）。

**Acceptance Scenarios**:

1. **Given** 注册表里渠道 `team-lark` 的 url 从测试群改为生产群 webhook，**When** 再次调 `notify(channel="team-lark", ...)`，**Then** 消息推到生产群；Agent 侧引用方式零改动
2. **Given** Agent 正文按名引用渠道，**When** 配置该 Agent，**Then** frontmatter 不含 notify_channels 字段，webhook 地址不进对话上下文

---

### User Story 3 - 出站与入站同一道墙（Priority: P2）

`notify` 往外推的是一次 HTTP 请求，发送前必须先过域名白名单校验——不能因为"往外推"就绕过去。白名单规则与 `http_post` 共享同一份，不新增任何 Sandbox 概念。这是"接口先行"从图纸变实物的第一站：本节先接进 Sandbox 接口（测试 mock），WhitelistSandbox 三层白名单实现归 23/24 节。

**Why this priority**: 安全是地基不是补丁；坑十钉死——`enforce` 先于 `send`，顺序反了就是漏洞（白名单被"往外推"绕过）。这也是 Sandbox 接口的第一个消费方（002 contracts/sandbox.md 明列）。

**Independent Test**: InOrder 顺序断言（mock Sandbox + mock adapter）：enforce(HTTP_REQUEST, url) 必须先于 send 被调用；域名拒绝路径：enforce 抛违规异常时 send 不得被调用。

**Acceptance Scenarios**:

1. **Given** 一次 notify 调用，**When** 执行链路走到发送前，**Then** 先执行 `enforce(HTTP_REQUEST, url)` 校验、校验通过后才执行 `send`
2. **Given** 目标 url 不在白名单，**When** 调 notify，**Then** 明确报错且不发生任何外发请求
3. **Given** adapter 显式映射表不含目标渠道的 type，**When** 调 notify，**Then** 明确报错（不靠扫描容器猜 adapter）

---

### User Story 4 - 定时日报 Agent 的出口（Priority: P3）

天气/科技日报 Agent 到点自动跑完一整套 ReAct 循环后调 `notify(channel="team-lark", content="今日天气…")`，结果主动推到群里。这是 Demo 一钟推版与 Demo 二/三的出站契约——本节先把"往外推"这个能力本身交付掉，定时触发（第 25 节）与工具集注册（第 20 节）就位后完整版自动生效。

**Why this priority**: 跨节契约的价值兑现依赖 20/25 节；本节交付能力 + mock 单测，注册进工具集与生产接线按契约后补，不改动本节已验收行为。

**Independent Test**: 本节以交付物级验证为主（NotifyTools 类存在、execute 链路正确、mock 单测绿）；"LLM 在对话里自动调 notify"的端到端版在 20 节后补验（需求文档明确留白）。

**Acceptance Scenarios**:

1. **Given** NotifyTools 已按 AxionTool 抽象交付（getName="notify"、schema 两参数、execute 四步），**When** 20 节将其注册进工具集，**Then** 无需改动本节任何代码即可被 LLM 按名调用
2. **Given** 后续节（25 定时、31 Demo）接入出站推送，**When** 它们调 notify，**Then** 本节交付的接口与表口径保持不变

---

### Edge Cases

- 注册表里没有该渠道名 → 明确报错，不静默、Agent 不会以为发出去了；审计落 success=false
- `channel` 缺省且注册表恰好一条渠道 → 取它；多条或为空 → 明确报错要求显式指定 channel（拍板口径：推错群是不可见的错误，把出错窗口压到最小）
- adapter 显式映射表无对应 channelType → 明确报错
- webhook 返回 5xx 或网络失败 → 异常原样上抛、不静默吞掉（坑十一：吞掉 = Agent 以为发出去了）；审计落 success=false、retryable=true
- webhook 返回 HTTP 200 但业务失败（errcode/code 字段）→ 核心阶段不解析，只认 HTTP 状态码（明确留白，人工验收留意）
- 4xx（URL 错/token 失效）与 5xx 重试语义不细分 → 统一 retryable=true 口径，重试与否由 LLM 下一轮判断（留白）
- 消息长度超平台上限 → 由平台截断或拒绝，本节不做截断处理（留白）
- 重复注册同一渠道名 → name 主键唯一约束生效，拒绝第二行
- description 不填 → 可空，不影响解析与推送

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: 系统 MUST 定义出站通知通道的统一接口 `NotifyChannelAdapter`——唯一方法 `send(NotifyTarget target, String content)`，表达"把一条内容送到某个通知目标"的意图；签名 MUST NOT 出现"webhook""企业微信""飞书"等任何一档实现特有的词；新增渠道只新增实现类，不改接口、不改调用方（接口先行）
- **FR-002**: 系统 MUST 定义 `NotifyTarget`（channelType + config: Map<String,String>）；具体是 webhook 地址还是别的认证信息由实现类自己解释，接口层不携带实现细节
- **FR-003**: 系统 MUST 提供核心阶段唯一实现 `WebhookNotifyAdapter`：对 target.config 里的 url 发 POST（JSON content-type、body 为通用 text 格式 `{"msgtype":"text","text":{"content": content}}`——2026-09-05 人工验收 40008 实锤修正：企业微信与钉钉共用此形态，飞书归扩展阶段专用 Adapter）；URL 只从 NotifyTarget.config 取、MUST NOT 硬编码；webhook 返回 5xx 或网络失败时异常原样上抛、MUST NOT 静默吞掉
- **FR-004**: 系统 MUST 提供 `notify_channels` 全局注册表：name（PK，注册名）、type（渠道类型）、url、description（可空）四列；建表走手工 schema.sql 增量（MUST NOT 依赖自动迁移）；JPA 实体 + Repository 按既有存储口径延伸；Agent 配置 frontmatter MUST NOT 含 notify_channels 字段，webhook 地址不进对话、不进配置键
- **FR-005**: 系统 MUST 提供注册表解析服务 `NotifyChannelRegistry`（纯数据）：按渠道名解析出 NotifyTarget（channelType = 表 type 列，config 含 url 与渠道 name）；查不到 MUST 明确报错；`channel` 缺省口径——注册表恰好一条渠道才允许缺省取它，多条或为空时 MUST 明确报错要求显式指定 channel；adapter 选择不在此服务内
- **FR-006**: 系统 MUST 提供 `NotifyTools`（内置 Tool `notify`，实现 AxionTool 抽象）：入参 content 必填、channel 可选；execute 四步顺序钉死——① 按 channel 名从注册表解析 NotifyTarget（缺省口径见 FR-005）② 按 target.channelType() 从装配处显式 Map<channelType, NotifyChannelAdapter> 选 adapter（MUST NOT 靠容器扫描），未知 type MUST 明确报错 ③ sandbox.enforce(HTTP_REQUEST, url) MUST 先于 send（顺序违反 = 白名单被绕过）④ adapter.send(target, content)；成功返回 MUST 带渠道名（"已推送到 <渠道名>"，审计可查出推给了谁）
- **FR-007**: 系统 MUST 满足依赖与装配要求：WebhookNotifyAdapter 构造注入 HTTP 客户端并设 connect/read timeout（慢 webhook 不得拖死 ReAct 轮次）；adapter 显式映射由装配处构建（加新渠道 = 新增实现类 + 映射表加一行，已验收代码零改动）；`NotifyTools` 注册进工具集归第 20 节（本节交付类 + mock 单测，工具执行器当前注入空 Map 为既定口径）

### Non-Functional Requirements

- **NFR-001**: 全程同步阻塞执行，不引入异步编程模型；并发由 Java 21 虚拟线程承担（宪法 VII）
- **NFR-002**: 结构化 JSON 日志沿用既有地基；webhook URL 等渠道配置 MUST NOT 进日志参数
- **NFR-003**: 审计 day one：notify 成功与失败 MUST 都写入审计表——复用工具执行器既有成功/失败路径即满足，不新增审计逻辑（宪法 V）

### Key Entities *(include if feature involves data)*

- **通知渠道（notify_channels 行）**: 全局注册表条目——注册名（Agent 正文按此名引用）、渠道类型（核心阶段均为 webhook）、webhook 地址、可选描述；由 Web Service 节提供 CRUD，本节只交付表口径 + 仓储 + 解析
- **NotifyTarget**: 解析后的推送目标——channelType + config（含 url 与渠道 name）；接口层不含任何实现细节
- **审计记录（tool_invocations）**: notify 每次执行的落账——成功 result 带渠道名（"已推送到 team-lark"），失败带明确 error；复用既有审计路径

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 注册表配好一个渠道后，一次 notify 从调用到消息出现在目标群，链路完整走通（人工真 webhook 验收；单次推送秒级完成）
- **SC-002**: 100% 的 notify 调用（成功与失败）都在审计表留下记录，成功记录带渠道名
- **SC-003**: 全部失败路径（渠道不存在、缺省歧义、未知渠道类型、白名单拒绝、webhook 5xx/网络失败）均以明确错误呈现——零静默失败，由 harness 断言逐条覆盖
- **SC-004**: 推送目标从测试群换成生产群只需改注册表一行，Agent 配置与代码零改动
- **SC-005**: 自动化验收全绿（mvn clean verify：本节两批单测 + 前序 001/002/003 全部回归），无任何跳过/放宽

## Assumptions

- **前序交付物已就位、无缺口**：AxionTool/ToolResult 抽象（001）、工具执行器 + Sandbox 接口四件套 + 契约文档（002）、存储口径与手工建表脚本先例（003）均按现状实测确认（2026-09-03）；"Sandbox 纯接口、零实现、无人调用"与"工具集注册归第 20 节"是既定跨节契约，本节以 mock 承接，不构成缺口
- **Sandbox 实现后补**：本节注入 Sandbox 接口，测试用 mock；WhitelistSandbox 三层白名单实现归第 23/24 节（002 FR-7 契约），届时本节代码零改动
- **工具集注册后补**：NotifyTools 注册进工具执行器的工具 Map 与生产 bean 接线归第 20 节（003 FR-10 口径）；"LLM 在对话里自动调 notify"的端到端版在 20 节后补验
- **渠道 CRUD 归 Web Service 节**：本节只交表 + 仓储 + 解析服务，不做 REST 端点与 Web 管理页（拍板 2026-09-03）
- **核心阶段只做通用 webhook**：通用 text 格式（`msgtype/text/content`）覆盖企业微信与钉钉（2026-09-05 人工验收实测企业微信 errcode 0）；飞书 text 格式不同（msg_type/content/text）归扩展阶段专用 Adapter；签名算法、AccessToken 刷新、富文本卡片、body errcode 解析均明确不做；4xx/5xx 重试语义不细分、消息长度截断不做（有意留白）
- **外部依赖**：HTTP 客户端由既有 Spring 生态提供（Boot 3.5 自带，BOM 管理版本）；测试用本地假 webhook（MockWebServer，属单测层、不算外网依赖，全仓首次引入）；真实 webhook 地址由用户在人工验收时提供
- **无前序公共接口改造**：Agent 配置不动（notify_channels frontmatter 字段方案已拍板否决）、工具执行器/AxionTool/Sandbox 原样使用；对前序产物的变化仅为 schema.sql 增量追加 + axion-tool pom 增依赖，均为增量
