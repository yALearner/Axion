# Feature Specification: Agent 动态管理（012-agent-mgmt）

**Feature Branch**: `012-agent-mgmt`

**Created**: 2026-10-02

**Status**: Draft

**Input**: 需求文档 docs/requirements/012-agent-mgmt.md（Agent 动态管理——5.3 端点全集 + WorkspaceWatcher + per-agent 记忆/会话 + 管理台 Agent 页；口径 = 课件第 30 节「五、实现回写」最终实现）

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 管理台新建 Agent 即上线 (Priority: P1)

运营方在管理台 Agent 页只填 name + description 点「新建」，系统按模板脚手架出完整的 Agent 目录并完成注册——返回成功后**不重启进程**，这个 Agent 立刻出现在列表里，到它声明的定时点自动运行并把结果推送到指定渠道。

**Why this priority**: 这是 30 节的核心闭环——"说一句话 / 填个名字就上线一个会自己跑的 Agent"，前面所有节（目录=Agent、运行时注册、定时）都在为它铺路；没有它管理平台永远"只能看不能管"。

**Independent Test**: 空工作区下经管理台新建一个带 schedules 的 Agent → 不重启，Agent 列表立即可见、到点自动触发（可设每分钟的临时 cron 验证）、全程无需登录服务器。

**Acceptance Scenarios**:

1. **Given** 一个运行中的系统与空 Agent 列表，**When** 运营在管理台填 name+description 点「新建」，**Then** 返回成功且 Agent 列表立刻出现该 Agent，无需重启
2. **Given** 新建请求里的 name 与已有 Agent 重名，**When** 提交创建，**Then** 被拒（400），系统里不留下任何半成品目录
3. **Given** 新建的 Agent 声明了 schedules，**When** 到点，**Then** 它自动运行并推送结果（与手工定义的 Agent 行为一致）

---

### User Story 2 - 丢目录即上线、删目录即注销 (Priority: P1)

运维方不经过任何 API，直接往工作区的 agents 目录拷贝一个 Agent 目录（scp/git/编辑器），几秒内它就被登记、可用；把目录删掉/移走，几秒内它被注销、定时停止。

**Why this priority**: "上传即上线"的另一半——文件系统是 Agent 目录的真相源，实时监听是"动态管理"的本质；两条录入路径行为必须一模一样，这是 29 节立下的判断标准。

**Independent Test**: 运行中的系统下手工拷入一个 Agent 目录 → 几秒内出现在 Agent 列表；手工删除该目录 → 几秒内从列表消失、定时注销；再拷入一个坏目录（缺 AGENT.md）→ 日志有明确报错、系统其余部分不受影响。

**Acceptance Scenarios**:

1. **Given** 运行中的系统，**When** 手工往 agents 目录拷入一个合法 Agent 目录，**Then** 几秒内它出现在 Agent 列表并可被调用，全程免重启
2. **Given** 上述 Agent 已登记，**When** 手工删除该目录，**Then** 几秒内它从列表移除、其定时任务被注销
3. **Given** 一个无法解析的坏目录被拷入，**When** 监听器处理它，**Then** 日志记录明确错误并跳过，不影响其他 Agent 与监听本身

---

### User Story 3 - API 全生命周期管理 (Priority: P2)

业务系统通过 REST 接口完成 Agent 的创建、列表、详情、更新、删除与无状态调用，错误语义清晰（已存在/字段非法 → 400、查改删不存在 → 404），响应统一信封。

**Why this priority**: 业务系统"够得着"的入口——不摸文件系统也能完全管理 Agent；30 节之前只有 invoke 一个端点，管理能力为零。

**Independent Test**: 用接口依次执行 create → get/list → update → invoke → delete 全序列，逐项核对响应与错误码；更新 schedules 后旧定时不再触发、新定时生效。

**Acceptance Scenarios**:

1. **Given** 合法创建请求，**When** POST 创建，**Then** 返回 Agent 信息、立即可见可调用
2. **Given** 已存在的 Agent，**When** PUT 修改正文或 provider，**Then** 下一轮对话即用新配置（无需重启）；修改 schedules 时旧定时先注销、新定时注册
3. **Given** 不存在的 Agent 名，**When** GET/PUT/DELETE 该名字，**Then** 404；已存在/字段非法/生成产物非法 → 400 且原因可读

---

### User Story 4 - 按描述重生成 + 文件保存生效 (Priority: P2)

运营对某个已存在的 Agent 填一句自然语言描述，系统用大模型生成 AGENT.md 等文件草稿，**每个文件可编辑预览、不落盘**；确认后一键保存，写入即生效。工作区文件浏览器支持钻进 Agent 目录看文件内容并编辑保存，越界路径一律被拒。

**Why this priority**: "一句话生成"的新面孔（原始设计收窄为对已存在 Agent 的重生成）+ 管理台"真能管"所需的文件级操作面；生成是人机协作、必须人在环。

**Independent Test**: 对一个已存在 Agent 提交一句描述 → 返回可解析的文件草稿且磁盘无变化；改草稿后保存 → 目录文件更新、Agent 按新内容运行；传一个越界路径读文件 → 400。

**Acceptance Scenarios**:

1. **Given** 一个已存在 Agent，**When** 提交一句描述请求生成，**Then** 返回 AGENT.md 等文件草稿，磁盘与注册状态无任何变化
2. **Given** 生成返回的草稿（可能被用户改过），**When** 保存这组文件，**Then** 写入 Agent 目录并重新注册，下一轮即按新内容运行
3. **Given** 生成产出不是合法 AGENT.md（或未配置生成模型），**When** 请求生成/保存，**Then** 400 带可读原因（或 503），系统不写坏任何目录
4. **Given** 工作区文件浏览请求，**When** 传一个越界路径，**Then** 400 拒绝

---

### User Story 5 - 每个 Agent 专属记忆与固定会话 (Priority: P3)

管理台里每个 Agent 恰好一条固定会话，对话上下文可累积、不与其他 Agent 串扰；每个 Agent 有自己专属的长期记忆文件——Agent 自己通过 save_memory 写入、管理台只读展示。

**Why this priority**: 5.2 增补能力——把"记忆/会话"从全局收编到 Agent 维度（合"一个目录 = 一个 Agent"）；是管理台 5 tab 里最体现 Agent 个体性的两块。

**Independent Test**: 在 Agent A 的固定会话里发消息让它 save_memory，再查看：记忆落在 A 自己的目录里、B 的对话与记忆完全不受影响；多次向固定会话发消息，上下文持续累积（会话 id 恒定）。

**Acceptance Scenarios**:

1. **Given** Agent A 的固定会话，**When** 发消息并触发 save_memory，**Then** 记忆写入 A 自己目录下的记忆文件，管理台「记忆」tab 可读到
2. **Given** 连续向同一 Agent 的固定会话发多条消息，**Then** 恒为同一条会话、上下文累积，且与任何其他 Agent 的会话隔离
3. **Given** 无 Agent 上下文的环境（如 CLI 直接对话），**When** 使用记忆，**Then** 行为与改造前一致（回退全局记忆），既有三档记忆后端的对外契约不变

---

### User Story 6 - 删除可追溯 (Priority: P3)

删除一个 Agent 时系统先停它的定时、再移出索引、最后把整个目录**归档**（不物理删除）——它曾经干过的事仍可在审计记录里逐笔查证。

**Why this priority**: 严监管场景的底线要求——定义与行为都可追溯；同时钉死删除的时序（先停定时再动索引，反了会触发空指针）。

**Independent Test**: 对一个跑着定时任务的 Agent 执行删除 → 定时不再触发、列表移除、目录出现在归档区；历史审计记录（llm_calls/tool_invocations）仍完整可查。

**Acceptance Scenarios**:

1. **Given** 一个带定时任务的 Agent，**When** 删除它，**Then** 定时注销、从列表移除、整个目录出现在归档区而非消失
2. **Given** 已删除 Agent 的历史记录，**When** 查询审计，**Then** 它此前的 LLM 调用与工具执行记录逐笔可查

---

### Edge Cases

- 目录正在写一半时监听事件到达：注册失败记 WARN 跳过，不拖垮监听，后续事件再拾取
- 创建中途任一步失败：已写的目录被回滚删除，系统不留半个 Agent
- 删除顺序颠倒（先移出索引再停定时）：时序窗口内定时触发会空指针——顺序必须被测试钉死
- 更新 schedules：旧 cron 必须先行注销，否则新旧两个定时一起跑
- 文件读取路径穿越（../ 越界）：400 拒绝，这是文件浏览器的唯一安全要点
- 生成用模型未配置：返回可读 503，绝不向模型服务发送空模型名
- 大模型多吐代码围栏或非法内容：围栏剥除、非法产出 400，不写坏目录
- macOS 上 WatchService 不监听子目录内文件改动：编辑 AGENT.md 必须显式走更新重注册
- 记忆上下文置入后异常退出：finally 必须清除，否则下一个会话串号
- 手工删目录与 API 删除：必须走同一段注销代码，行为对称

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST 让三条录入路径（API 上传、手工丢目录、启动扫描）汇入同一段注册代码，行为、校验与报错完全一致
- **FR-002**: System MUST 提供实时目录监听——启动先全量扫描，之后实时监听 agents 目录变更：新增/修改 → 注册，删除/移走 → 注销；单个坏目录报错跳过，不拖垮监听
- **FR-003**: System MUST 在创建时按「校验（重名第一步就拒）→ 写目录 → 注册 → 注册定时」顺序编排，任一步失败回滚已写内容，不留半个 Agent
- **FR-004**: System MUST 在删除时按「注销定时 → 移出索引 → 目录归档」顺序执行，归档不物理删除
- **FR-005**: System MUST 支持更新：正文/配置覆写即时生效（无需重启）；schedules 变更必须先注销旧定时再注册新定时
- **FR-006**: System MUST 提供 Agent 管理的完整接口面（创建/列表/详情/更新/删除/无状态调用/生成文件/保存文件/会话两端点/记忆读取）与工作区文件浏览接口（目录树/读文件/写文件），统一响应信封；错误语义：已存在与字段非法 400（code 区分）、不存在 404、生成模型未配置 503
- **FR-007**: System MUST 对文件浏览的路径做防目录穿越校验（归一化后必须落在工作区内），越界 400；编辑 Agent 定义文件必须走更新重注册，其余文件直接写盘
- **FR-008**: System MUST 支持按描述重生成文件：一次模型调用产出草稿（不落盘、不注册、可预览可改），产出必须可解析、非法产出 400；保存时必须先校验再写盘，写入即生效；生成调用必须落审计记录；生成所用 provider/model 取系统默认配置，model 未配置时返回可读 503
- **FR-009**: System MUST 让每个 Agent 拥有专属记忆文件（Agent 通过 save_memory 写入自己的记忆，管理台只读展示）；无 Agent 上下文时回退全局记忆，既有记忆后端的对外契约零改动；全局记忆查询接口退役、由 per-agent 记忆接口取代
- **FR-010**: System MUST 为每个 Agent 提供一条固定会话（channel=admin、user=console、幂等获取恒为同一条），支持查询会话内容与向会话发消息触发 Agent 运行
- **FR-011**: System MUST 在管理台提供 Agent 管理页（列表 + 新建 + 详情五个分区：基本信息/生成/文件/会话/记忆），并移除已退役的全局记忆页
- **NFR-001**: 全程同步阻塞执行模型；目录监听是后台守护线程、不进入请求处理链路
- **NFR-002**: 生成类调用（模型调用）成败都落审计记录
- **NFR-003**: 运行时注册入口必须执行与启动路径相同的工具引用校验（同一校验、同一告警口径）

### Key Entities

- **Agent**：一个目录 = 一个 Agent；属性含名字、描述、provider/model 配置、工具列表、定时计划与指令正文；是本节唯一对外管理的资源
- **Agent 目录**：真相源落文件系统——AGENT.md（配置+指令）+ 可选 scripts//skills//REFERENCE.md；删除时归档而非物理删除
- **固定会话**：每个 Agent 恰一条，channel=admin、user=console；承载管理台对话的上下文累积
- **记忆文件**：每个 Agent 自己的长期记忆（核心区+归档区），由 Agent 经 save_memory 写入
- **审计记录**：LLM 调用与工具执行的逐笔账，删除 Agent 后仍可查

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 新建 Agent 从提交到出现在列表，全程零重启；从提交到可被调用 ≤ 数秒级
- **SC-002**: 手工拷入/删除 Agent 目录后，几秒内系统状态与之同步（登记/注销），免重启
- **SC-003**: 三种录入方式（API、丢目录、启动扫描）对同一目录的校验结果与报错完全一致（同一套规则）
- **SC-004**: 删除 Agent 后其目录完整保留在归档区、历史审计逐笔可查（0 丢失）
- **SC-005**: 管理台可完成「新建 → 查看 → 生成 → 保存 → 会话对话 → 删除」全流程，每个页面在空数据/加载中/出错三种状态下均有明确反馈（无白屏）

## Assumptions

- 内网环境假设延续：本节不引入认证鉴权（谁能建 Agent/读文件由网络边界约束，扩展阶段随 API Key/RBAC 补）
- JSON 创建只写 AGENT.md 与模板脚手架；带脚本的复杂 Agent 走手工丢目录路径（multipart/zip 上传归扩展阶段）
- 复用既有会话与审计存储（无新表）；归档是目录操作而非存储层变更
- 生成用 provider/model 由系统默认配置提供（新配置键），生成的草稿必须人在环确认后才生效
- 全局记忆接口与管理台全局记忆页随 per-agent 记忆上线而退役（课件 5.2.1 明示，不留双路径）
- 启动加载失败的 Agent 仍只记日志、管理台不展示失败清单（011 债③延续，课件未点名端点）
