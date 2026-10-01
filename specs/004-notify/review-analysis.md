# Review Analysis: 004-notify 实现复盘（偏差与不足）

> 生成时间：2026-09-05（S6 交付后、人工验收前）。
> 对象：axion-tool（notify/builtin 子包 5 类）+ axion-storage（实体/仓储/schema 增量）+ 4 个测试类。
> 定位：交付后的偏差复盘与不足清单——结论供用户拍板是否修复；已修复项须回写本文档与需求文档。
> 行号以 spotless 格式化后的定稿为准。

## 一、偏差分析（实现 vs 方案设计）

| # | 偏差点 | 位置 | 性质 | 结论 |
|---|--------|------|------|------|
| D1 | `.uri(URI.create(url))` 而非课件骨架的 `.uri(url)` | WebhookNotifyAdapter.java:38 | 防御性增强 | 骨架 `.uri(String)` 按 URI 模板解析，URL 含 `{`/`}` 会误展开；`URI.create` 更严格。功能零偏差，保留 |
| D2 | `NotifyTarget` 紧凑构造器拷贝 + 访问器覆盖，非骨架纯 record | NotifyTarget.java | 防御性增强 | SpotBugs EI_EXPOSE_REP 门禁要求，JsonSchema 同款先例。零功能偏差 |
| D3 | **无 `@Component`**（课件骨架有） | NotifyTools.java:30 / WebhookNotifyAdapter.java:19 | 拍板级主动偏差 | G4-C1 钉死（boot 扫描 com.axion 全树，误加启动即崩）；深析见 §三 |
| D4 | 未知 channelType 显式 null 检查（骨架无） | NotifyTools.java:76-79 | 实现补全需求 | FR-6 明写"未知 type → 明确报错"，补上骨架漏掉的检查 |
| D5 | mockwebserver 显式 4.12.0（需求文档原称"Boot BOM 管理"） | axion-tool/pom.xml | 文档修正 | H3 实测修正（Boot 3.5 BOM 不管理 okhttp3），需求文档/研究文档已同步 |
| D6 | body 格式 `{"content":...}` → 通用 text 格式 `{"msgtype":"text","text":{"content":...}}` | WebhookNotifyAdapter.java send 方法 | **人工验收实锤的功能缺陷修正** | 2026-09-05 真实企业微信返回 `errcode: 40008 invalid message type`（HTTP 200、群里收不到）——课件骨架 body 格式三平台实际都不认；方案 A（用户拍板）：企微+钉钉共用 msgtype/text/content，飞书归扩展阶段专用 Adapter。接口签名与架构零改动 |

**总结论：无功能性偏差**。2 处防御性增强 + 1 处拍板级主动偏差（D3）+ 1 处文档修正，全部已留痕（flow-status / tasks.md / javadoc）。

## 二、不足清单

### 稳定性

| # | 级别 | 不足 | 具体信息 | 解决方案 | 状态 |
|---|------|------|---------|---------|------|
| S1 | 🔴 | `content` 参数零防护：① 缺失 → NPE（error_message 机器语言，LLM 不可读）② JSON null → `asText()` 返回字面 `"null"` → **群里真的收到"null"**。schema required 只是提示不是约束 | NotifyTools.java:71 | execute 开头校验：缺失/isNull/isBlank → `ToolResult.failure("参数 content 必填且不能为空", false)`（走 ToolExecutor 审计 success=false）；NotifyToolsTest 加 3 用例 | ✅ 已修复（2026-09-05 拍板） |
| S2 | 🟡 | timeout 契约无跨节钉死：唯一落实点是第 20 节装配处，contracts 不变量 1~8 没有它——§20 漏设 = 慢 webhook 把整个 ReAct 轮次挂死 | WebhookNotifyAdapter.java:16-17（仅 javadoc）；contracts/notify-channel.md（缺） | 契约文件增补不变量第 9 条：「装配处 MUST 用 Boot 自动配置的 RestClient.Builder 构建并设 connect/read timeout（建议 connect 3s / read 10s）」+ §20 任务提醒 | ✅ 已修复（2026-09-05 拍板，不变量 9 已落） |
| S3 | 🟡 | JPA 实体映射零覆盖（测试盲区）：storage 测试纯 JDBC 验表不验 JPA；tool 测试 mock repository。@Column 映射错 → 全部单测依然绿，生产 Registry 一调就挂（003 的 SessionEntity 有 SessionManagerTest+实跑兜底，004 实体没有） | NotifyChannelEntity.java 全类 | 人工 harness 步骤二第 4 用例（真实 save→findById 回读断言，已沉淀进 quickstart.md）；中期评估 storage 加 @DataJpaTest 集成测试 | 已由人工项兜底 |
| S4 | ⚪ | URL 空串无防线：NOT NULL 但空串合法 → `URI.create("")` 相对 URI → RestClient 抛错（不静默 ✓ 但错误绕道） | WebhookNotifyAdapter.java:35-38 | Registry.resolve 校验 url 非空（报错带渠道名）；或归 Web 节 CRUD 校验。二选一 | ✅ 已由 E1 修复顺带解决（`url()` 访问器缺失/空串明确报错，落审计 success=false） |

### 可扩展性

| # | 级别 | 项 | 具体信息 | 结论/方案 | 状态 |
|---|------|-----|---------|----------|------|
| E1 | 🟡 | config 键字面量散落 5 处（"url"/"name"），键名漂移无编译期保护 | NotifyChannelRegistry.java:49、NotifyTools.java:81/84、WebhookNotifyAdapter.java:35 + 测试 2 处 | NotifyTarget 内定义键常量（KEY_URL/KEY_NAME）+ 便捷访问器 url()/name()（空值防御），调用方改用 | ✅ 已修复（2026-09-05 拍板） |
| E2 | ✅ | 加新渠道 = 新增实现类 + 映射表加一行，已验收代码零改动（契约不变量 5 已钉） | — | 正面结论 | — |
| E3 | ✅ | 未知 type 防线在位；表不加 CHECK 约束是刻意的（扩展阶段新 type 不被卡） | NotifyTools.java:76-79 | 正面结论，设计选择 | — |

### 性能

| # | 级别 | 项 | 具体信息 | 结论/方案 | 状态 |
|---|------|-----|---------|----------|------|
| P1 | ⚪ | 每次 notify 一次 DB 往返，缺省路径 findAll() 全表扫 | NotifyChannelRegistry.java:40 | SQLite 本地 <1ms、条级规模 → 当前无碍。核心阶段刻意不加缓存（CRUD 归 Web 节，缓存失效无处安放，一致性优先）；扩展阶段若成瓶颈再引入 Caffeine + 失效策略 | 已知权衡 |
| P2 | ⚪ | 测试裸 RestClient.builder() 无连接池无 timeout（每请求新建连接） | WebhookNotifyAdapterTest.java:31 | 仅测试无碍；生产装配必须用 Boot 自动配置 builder（默认池化）——并入 S2 第 9 条一起钉 | 记录 |
| P3 | ✅ | 同步阻塞 + 虚拟线程 ✓；无共享可变状态（RestClient/repository 线程安全、Map.copyOf 不可变）→ 并发安全 ✓ | 全类 | 正面结论 | — |

## 三、D3 深析：为什么"不加 @Component"是唯一解（拍板级主动偏差的合理性论证）

> 用户提问记录（2026-09-05）："无 @Component 这样做合理吗，相比之前哪种更好？"——本节给出完整论证，结论为：**当前方案（A）不仅合理，而且是三条拍板/契约共同推出的必然推论，优于骨架方案（B）与条件注解方案（C）**。

### 方案对比

| 维度 | A：无注解 + 20 节显式 @Bean（当前） | B：课件骨架 @Component + 自动注入 | C：@Component + 条件注解（@ConditionalOnBean 等） |
|------|-----------------------------------|----------------------------------|--------------------------------------------------|
| 启动安全 | ✅ 任何时刻上下文缺依赖都不会崩（bean 定义与依赖就位严格同步） | ❌ 本节立即被全树扫描拾取：RestClient/Sandbox/Map bean 均不存在 → UnsatisfiedDependencyException，003 已验收的 chat/serve 启动即崩 | ❌ 条件注解在 bean 定义期评估，依赖 bean 不存在时仍需精确的降级编排；引入评估顺序新坑 |
| 与 002 契约相容 | ✅ "Sandbox 纯接口、零实现、无人调用"（002 FR-7）保持到 23/24 节 | ❌ 被迫提前造 Sandbox 假 bean——放行 stub 会让白名单形同虚设（安全漏洞）或空实现吞异常 | ❌ 同 B，且条件注解无助于"零实现"契约 |
| 与拍板"channelType 显式映射"相容 | ✅ 装配处 `Map.of("webhook", adapter)` 键=channelType，逐字符合拍板 | ❌ **决定性冲突**：Spring 对构造注入的 `Map<String, NotifyChannelAdapter>` 按 **bean name** 键控（默认 "webhookNotifyAdapter"），`adapters.get(target.channelType())` 恒为 null → "未知通知渠道类型"永远报错。骨架的 @Component 形态在拍板口径下是坏掉的接线 | ❌ 同 B，需 @Qualifier 逐个点名 = 把显式映射伪装成扫描，复杂度不降反升 |
| 与宪法 III 哲学 | ✅ 显式装配 > 隐式扫描，与宪法反对"靠扫描发现/区分"一致 | ❌ 恰是宪法 III 反对的模式（靠容器扫描组装） | ❌ 同 B |
| 时序契约 | ✅ 裁决 7（装配归 20 节、003 FR-10 空 Map 口径）逐字成立 | ❌ 被迫把装配提前 → 超交付清单 + 推翻裁决 7 | ⚠️ 可延迟但无必要 |
| 可测试性 | ✅ 纯构造注入，3 个测试类全纯 Mockito、零 Spring 上下文，快且稳 | ⚠️ 单测需手工构造或起上下文 | ⚠️ 同 B |
| 20 节漏接线的暴露 | ✅ 漏 @Bean 会被工具集空 Map 兜底暴露（notify 不可用 → 测试红） | ⚠️ 漏接线同样存在，且"类已带注解"给人已接线的错觉 | ⚠️ 同 B |

### 最硬的一条理由：@Component 与拍板口径直接冲突（骨架形态在拍板下是"坏掉的接线"）

拍板（2026-09-04）定死：adapter 显式 `Map<channelType, NotifyChannelAdapter>` 按 **channelType** 选择。但 Spring 对构造注入的
`Map<String, NotifyChannelAdapter>` 自动装配时，**键是 bean name（默认 "webhookNotifyAdapter"），不是 channelType**：

```java
// 骨架 B 若真落地：
adapters.get(target.channelType())   // 找 "webhook"，但 Map 里只有 "webhookNotifyAdapter"
// → 恒 null → "未知通知渠道类型" 永远报错
```

要让骨架 B 工作，必须 `@Qualifier` 逐个点名或显式命名 bean——这等于把显式映射伪装成组件扫描，复杂度不降反升。所以 D3 是拍板结论（显式映射）的必然推论，不是偷懒。

### 三条交叉约束（前置契约 → @Component 方案的冲突）

| 前置契约 | @Component 方案的冲突 |
|---------|---------------------|
| 002 FR-7：Sandbox 纯接口、**零实现**（实现归 23/24 节） | NotifyTools 的 @Component 需要 Sandbox bean → 被迫提前造假 bean（放行 stub = 白名单形同虚设的安全漏洞）或推翻 002 契约 |
| 裁决 7：RestClient/adapter Map 装配归第 20 节 | RestClient/Map bean 本节不存在 → 全树扫描立即拾取 → `UnsatisfiedDependencyException`，003 已验收的 chat/serve 启动即崩（boot 是 `scanBasePackages = "com.axion"` 全树扫描） |
| 003 FR-10：工具集空 Map、第 20 节替换 | 与"本节只交类、不交 bean"的既定节奏冲突 |

### 论证要点

1. **D3 不是"删两个注解"的机械适配，而是拍板结论的必然推论**：拍板（2026-09-04）定死 adapter 显式 Map 按 channelType 选——Spring 的 `Map<String, X>` 自动注入按 bean name 键控，语义直接冲突。保留 @Component 会让 `adapters.get(target.channelType())` 恒失败，即骨架形态在拍板口径下是一段"看起来合法、跑起来必错"的代码。
2. **三个前置契约交叉推出唯一解**：002 FR-7（Sandbox 零实现，本节无 bean 可注）+ 裁决 7（RestClient/Map 装配归 20 节）+ 003 FR-10（工具集空 Map、20 节替换）——三者都要求"本节只交类、不交 bean"，@Component 与之全部冲突。
3. **防御面**：boot 启动类 `scanBasePackages = "com.axion"` 全树扫描（003 实测修复过的启动路径），任何 @Component 立即生效——后果是已验收的 chat/serve 回归崩溃，而不会等到 20 节才暴露。
4. **唯一成本**：20 节装配处多写一个 `@Bean`（一行），换来"bean 存在 ⇔ 依赖就位"的时序不变量。已通过 javadoc（NotifyTools.java:27-28、WebhookNotifyAdapter.java:16-17）与 tasks.md T011/T012 把理由钉死，防止 20 节有人"顺手加回 @Component"。

### 防回退措施

理由已三重钉死：`NotifyTools.java:27-28` javadoc、`WebhookNotifyAdapter.java:16-17` javadoc、`tasks.md` T011/T012（G4-C1）——防止第 20 节有人"顺手加回 @Component"。若 20 节交付时显式 `@Bean` 出现真实摩擦，回到本文件 §三重新评估，而不是回退注解。

### 结论

**D3 保留，不翻案**。方案 A 在启动安全、契约相容、宪法哲学、可测试性四个维度全面优于骨架 B；条件注解 C 是"为像骨架而增加复杂度"，无收益。若 20 节交付时发现装配处显式 @Bean 有真实摩擦，再回到本文件重新评估，而不是回退 @Component。

## 四、处理状态追踪

- ✅ 已修复（2026-09-05 用户拍板）：S1、S2、S4、E1——代码（NotifyTarget/NotifyTools/WebhookNotifyAdapter/Registry + NotifyToolsTest 3 新用例）+ 契约（不变量 9）+ 需求文档（FR-2/FR-6/骨架/修订说明）已同步，全量门禁重跑全绿
- 已兜底：S3（人工 harness 步骤二第 4 用例）
- 已记录不处理：P1、P2（核心阶段刻意留白/权衡）
- 已修复项回写：本文件 §二 状态列 + 需求文档（如涉及）+ flow-status 停止清单记录

## 五、修复记录（2026-09-05，用户拍板后执行）

| 项 | 改动 | 落点 |
|----|------|------|
| **S1** content 必填校验 | 缺失 / JSON null / 空串 → `ToolResult.failure("参数 content 必填且不能为空", false)`（不 NPE、不推字面 "null"，失败走 ToolExecutor 审计） | NotifyTools.java execute 首段 |
| **E1** config 键常量 + 访问器 | `KEY_URL`/`KEY_NAME` 常量 + `url()`/`name()` 访问器（缺失/空串明确报错），Registry/NotifyTools/Adapter/两处测试全部改经访问器取用，键名字面量零散落 | NotifyTarget.java |
| **S4**（顺带解决） | `url()` 访问器内置空串防线——不再发生 `URI.create("")` 绕道错误，改为审计落 `success=false` 的明确报错 | NotifyTarget.url() |
| **S2** 跨节契约钉死 | 不变量第 9 条：「装配处 MUST 用 Boot 自动配置的 `RestClient.Builder` 构建并设 connect/read timeout（建议 connect 3s / read 10s），MUST NOT 用裸 builder」 | contracts/notify-channel.md |

**测试与文档同步**：

- `NotifyToolsTest` 新增 3 用例：`missingContentFails` / `nullContentFails` / `blankContentFails`（失败返回 + 零外发 + 零 enforce）→ 11/11 全绿
- 需求文档同步：FR-2（键约定）、FR-6（content 校验口径）、核心代码骨架（execute 更新为校验版）、修订说明补一条
- flow-status.md：停止清单触发记录补一条（复盘修复经用户拍板）

**门禁证据**：`mvn clean verify` BUILD SUCCESS，**81 tests / 0 失败**（78 → 81，+3 content 反例；全静态门禁含 Spotless/Checkstyle/PMD p3c/SpotBugs+FindSecBugs/ErrorProne 全过）。

**剩余已知权衡**（保持记录不处理）：

- P1：注册表无缓存（每次 notify 一次 DB 往返）——核心阶段一致性优先，扩展阶段评估 Caffeine + 失效策略
- P2：测试裸 `RestClient.builder()` 无连接池——仅测试场景无碍，生产装配按不变量 9 用 Boot 自动配置 builder
- S3：JPA 实体映射单测盲区——由人工 harness 步骤二第 4 用例兜底，中期评估 storage 加 @DataJpaTest 集成测试
- **D6（人工验收 40008）**：已按方案 A 修复并验证闭环（2026-09-05）——body 改通用 text 格式（`msgtype/text/content`），需求文档 FR-3/骨架/修订说明、spec FR-003/Assumptions、contracts 核心阶段实现、tasks T011、quickstart 同步；真实企业微信群机器人收到中文消息（errcode 0），全量门禁 81 tests 全绿；乱码根因 = curl/Windows 控制台编码，与实现无关（Java/Jackson 发送 UTF-8 正常）

## 六、代码风格决策（2026-09-05 用户拍板，全项目口径，已同步 CLAUDE.md 设计原则）

| 议题 | 决策 | 理由摘要 |
|------|------|---------|
| Bean get/set 注解化（Lombok） | **不引入**：实体手写 getter、DTO/值对象用 record | 收益仅 2 个实体；record 已原生覆盖 DTO 场景；p3c"谨慎使用 Lombok" + SpotBugs（EI_EXPOSE_REP 会随 @Getter 回归）/ErrorProne/NullAway 兼容成本；实体"无 setter 收口修改路径"是 003 刻意设计，@Setter/@Data 会摧毁它 |
| 模块内 controller/service/dao/model 分层分包 | **维持现状**（模块即层）；子包仅按领域、仅在类多模块 | 9 模块本身就是层边界（web=controller、storage=dao、core=service/domain），再分层 = 分层做两遍；硬拆产生"每层 1 个文件"空层（provider 5 类/memory 1 类）；改包名 = 前序 001~004 交付物公共接口连锁改动（停止清单第 4 条范畴），应独立工程决策而非混入 feature |
