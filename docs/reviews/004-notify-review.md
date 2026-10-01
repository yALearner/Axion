# 004-notify 代码 Review 指南

> 生成：2026-09-05（交付后复盘 + 人工验收闭环后）；对应 PR #5（已 MERGED）。
> 复盘全记录见 `specs/004-notify/review-analysis.md`（偏差 D1~D6 + 不足 S1~S4/E1/P1~P2 + D3 深析 + 修复记录），本指南是 review 导航。

## 一、全景：一条消息从"LLM 决定推"到"群里收到"

```
LLM tool call（channel=team-lark, content=...）
  → ToolExecutor.execute（按名调度 + 成败都落 tool_invocations 审计）
  → NotifyTools.execute 四步钉死：
      ① registry.resolve(channel)         —— 全局注册表解析（缺省：恰好一条渠道才允许）
      ② adapters.get(target.channelType())—— 装配处显式 Map 选 adapter（不靠容器扫描）
      ③ sandbox.enforce(HTTP_REQUEST, url)—— 先于 send（坑十：顺序反了就是漏洞）
      ④ adapter.send(target, content)
  → WebhookNotifyAdapter：RestClient POST，body 通用 text 格式 {"msgtype":"text","text":{"content":...}}
  → 企业微信群机器人收到消息
成功返回 "已推送到 <渠道名>"（审计 result_json 带渠道名）
```

本课是 **Sandbox 接口的第一个消费方**（002 contracts/sandbox.md 消费方列表明列）；Sandbox 注入接口、测试 mock（WhitelistSandbox 归 23/24 节）；工具集注册归第 20 节（003 FR-10 空 Map 口径）。

## 二、逐文件梳理

### axion-tool/com/axion/tool/notify（接口层 + 解析，4 个文件）

| 文件 | 职责与关键点 |
|------|-------------|
| `NotifyChannelAdapter` | 接口先行：唯一方法 `send(NotifyTarget, String)`，签名零渠道实现词（不出现 webhook/企微/飞书） |
| `NotifyTarget` | record：channelType + config；**防御拷贝**（紧凑构造器 + 访问器覆盖，SpotBugs EI_EXPOSE_REP 同款 JsonSchema 先例）；`KEY_URL`/`KEY_NAME` 键常量 + `url()`/`name()` 访问器（缺失/空串明确报错——E1/S4 修复） |
| `WebhookNotifyAdapter` | 构造注入 `RestClient`（EI_EXPOSE_REP2 抑制注解，001 先例）；send：URL 经 `target.url()` 取 → POST JSON → **body 通用 text 格式**（40008 修复点，见清单 2）→ `toBodilessEntity()` 异常原样上抛（坑十一） |
| `NotifyChannelRegistry` | **纯数据**：findById / findAll；未命中明确报错；**缺省三态**（恰好一条取它 / 多条报错 / 空报错，拍板口径）；adapter 选择不在此类 |

### axion-tool/com/axion/tool/builtin（工具本体）

| 文件 | 职责与关键点 |
|------|-------------|
| `NotifyTools` | implements AxionTool：getName="notify"；schema content 必填/channel 可选；execute 开头 **content 必填校验**（缺失/JSON null/空串 → `ToolResult.failure`，S1 修复：不 NPE、不推字面 "null"）→ 四步钉死 → 未知 type 明确报错 → `ToolResult.success("已推送到 " + name)` |

### axion-storage（数据层）

| 文件 | 关键点 |
|------|--------|
| `schema.sql` 增量 | `notify_channels`（name PK / type / url / description 可空；CREATE IF NOT EXISTS，坑八口径） |
| `NotifyChannelEntity` | JPA 实体 4 字段；**无 setter**（修改路径收口，与 SessionEntity 同风格） |
| `NotifyChannelRepository` | `JpaRepository<NotifyChannelEntity, String>`（Web 节 CRUD 直接消费） |

### pom（5 项依赖，全部经拍板/补列）

axion-storage（compile，Registry 所需）+ spring-web（RestClient）+ spring-boot-starter-test + mockwebserver **4.12.0 显式版本**（Boot BOM 不管 okhttp3，H3 实测修正）+ spotbugs-annotations（provided，EI_EXPOSE_REP2 抑制）。

### 测试（4 类 21 用例，坑 ↔ 测试对号）

| 坑/点 | 测试落点 |
|------|---------|
| 坑十 enforce 先于 send | NotifyToolsTest.enforcesBeforeSend（InOrder）；violationBlocksSend（拒绝 → send 零调用） |
| 坑十一 5xx/网络失败上抛 | WebhookNotifyAdapterTest.throwsOnHttp500 / throwsOnNetworkFailure |
| 40008 body 格式 | WebhookNotifyAdapterTest.sendPostsContentJson（断言 msgtype/text/content 包装） |
| S1 content 校验 | NotifyToolsTest.missingContentFails / nullContentFails / blankContentFails |
| 缺省口径三态 | RegistryTest.defaultChannel* 三例；ToolsTest.defaultChannel* 两例 |
| 换渠道不碰 Agent | RegistryTest.sameNameNewUrlResolvesToNewUrl |
| 坑八手工建表 | RepositoryTest 三例（4 列/唯一约束/description 可空） |

## 三、重点 review 清单（按风险排序）

1. **execute 四步顺序（坑十）**——`NotifyTools.java:81` enforce 必须先于 `:83` send；这是本课最该盯的一段，顺序反了 = 白名单被"往外推"绕过
2. **body 通用 text 格式（40008）**——`WebhookNotifyAdapter.java` send 的 `Map.of("msgtype","text","text",Map.of("content",content))`；**最易被"回退到课件骨架 `{"content":...}`"的改动点**——骨架格式企业微信判 invalid message type，已有真机实锤
3. **异常不吞（坑十一）**——WebhookNotifyAdapter `toBodilessEntity()` 无 catch；吞掉 = Agent 以为发出去了
4. **无 @Component（G4-C1）**——NotifyTools/WebhookNotifyAdapter/Registry 均无组件注解；**最易被"顺手加回"的改动点**——boot 扫描 com.axion 全树，误加启动即崩（D3 深析见 review-analysis.md §三，含"Spring Map 注入按 bean name 键控 vs channelType 拍板"的决定性论证）
5. **content 必填校验（S1）**——NotifyTools.execute 首段三条件；防 NPE 与字面 "null" 入群
6. **缺省口径三态（拍板）**——Registry.resolve(null) 恰好一条才取；推错群是不可见的错误
7. **依赖方向与建表**——tool→storage 单向（宪法依赖方向）；schema.sql 增量不依赖 ddl-auto（坑八）

## 四、刻意留白（review 时不要当成缺陷报）

- **无装配类、无 @Bean、无工具集注册** → 第 20 节（裁决 7 / 003 FR-10）
- **Sandbox 是接口 + mock**，无 WhitelistSandbox → 第 23/24 节（002 FR-7）
- **body errcode 不解析**（"HTTP 200 但群里没收到"不识别）、4xx/5xx 重试不细分、消息长度截断 → 需求文档明确留白
- **注册表无缓存** → P1 已知权衡（CRUD 归 Web 节，缓存失效无处安放）
- **无渠道 CRUD REST** → Web Service 节
- **飞书不兼容** → 扩展阶段专用 Adapter（msg_type/content/text 形态不同）
- **测试裸 RestClient.builder() 无 timeout** → 仅测试；生产装配按契约不变量 9（Boot 自动配置 builder + connect/read timeout）
- **NotifyChannelEntity 无 JPA 层单测** → 坑八 JDBC 测表 + 人工 harness 回读断言兜底（S3，中期评估 @DataJpaTest）

## 五、建议 review 顺序

1. `specs/004-notify/contracts/notify-channel.md`（契约先行：不变量 1~9 是全部代码的判据）
2. `NotifyTarget`（数据契约与防御拷贝）→ `NotifyChannelRegistry`（解析与缺省口径）→ `NotifyTools`（四步核心链）→ `WebhookNotifyAdapter`（40008 修复点）
3. storage 三件套（实体/仓储/schema 增量）
4. 测试 ↔ 坑对号（§二表格逐条比对）
5. `specs/004-notify/review-analysis.md`（复盘全记录：偏差 D1~D6、修复记录、代码风格决策 §六）

## 六、当前验收状态

- **PR #5 已 MERGED**（2026-09-05，merge commit fe6422d）；CI：build/style/Analyze/CodeQL 全绿，security 红为存量 spring-core 6.2.19 CVE（PR #1~#3 同款先例，本 PR 新增依赖无新增 CVE）
- `mvn clean verify` 全绿：81 tests（004 新增 21）+ 全部静态门禁
- 人工验收闭环：真实企业微信群机器人收到中文消息；40008 修复（方案 A）；落库核对通过；harness 已删除
- **剩余人工项**：① 接口中立性自查（换企业微信官方 SDK 实现，`send(NotifyTarget, String)` 签名应无需改动）② "LLM 对话内自动调 notify"端到端 → 第 20 节工具注册后补验
