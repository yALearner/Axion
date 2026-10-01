# Quickstart 验证指南: 009-web-service

> 验证分两层：机器判卷（harness 全绿）+ 人工项（serve 实跑 + 管理台/接口文档核对）。本节验收 = harness 全绿 + serve 实跑（需求文档「跑通标准」；Demo 四/五 在 31 节合并验收）。

## 前置

- Java 21 + Maven + Node（前端构建，frontend-maven-plugin 首次构建会下载 node/npm 依赖）
- 前序交付物就位（实测 2026-09-08）：AgentService.process / SessionManager（getOrCreate）/ ProfileRegistry.list / ToolRegistry / LongTermMemoryStore.load；axion-web 地基 5 件；ServeCommand 占位
- **⑨b/⑨d 实施前核实**：SessionManager 持久化时机（findById 一致性）+ Session 并发面（三触发源同场）

## 机器判卷：harness 全绿

```bash
mvn test -pl axion-web -am                          # 日常全跑（@WebMvcTest 切片 + GlobalExceptionHandlerTest）
mvn clean verify                                     # 收尾全量门禁（全绿，不写死用例数——007 ⑦e 口径；含 WebSmokeIT）
mvn -Dskip.npm package                               # 后端迭代打包跳过前端构建（拍板 B skip 开关）
```

关键回归点对号（需求文档验收标准 harness 表）：

| 测试类 | 关键回归 |
|--------|---------|
| SessionApiControllerTest | 超 32KB → 400 / Session 不存在 → 404 / **process 恰被调一次**（薄 Controller 机器证据） |
| GlobalExceptionHandlerTest | 每类异常映射约定状态码（400/404/503/504/500）/ **信封边界**（错误均 ErrorResponse、成功均 ApiResponse——拍板 B 漂移防线）/ **⑨a 回归：IllegalStateException → 500 非 503** / **500 不含内部异常 message（最值钱）** |
| WebSmokeIT | 真上下文 /health /info /profiles /tools 可达——JPA repository 扫描红线（18 节「Found 0 repositories」坑复发第一时间红） |
| SessionManager 回归（⑨b） | 重启后 findById 恢复历史 / 发消息后 findById 可见最新 |

最值钱回归（课件 §四原文，实现之前写最划算）：

```java
@Test
void 内部异常细节_绝不能出现在500响应里() {
    when(agentService.process(any(), any()))
        .thenThrow(new IllegalStateException("jdbc:sqlite:/data/axion.db connect failed"));
    mockMvc.perform(post("/api/v1/sessions/s-1/messages").contentType(JSON).content(body))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.errorCode").value(500))                          // 拍板 B：错误走 ErrorResponse 信封
        .andExpect(jsonPath("$.message").value("内部错误"))                      // 统一话术
        .andExpect(content().string(not(containsString("jdbc:sqlite"))));       // 连接串这类内幕一个字不漏
}
```

## 人工项（做完怎么验）

```bash
axion serve                                          # 启动，默认 8080（只配 DEEPSEEK_API_KEY 一个 key 就起得来——eager 装配已排除）
curl -X POST localhost:8080/api/v1/sessions           # 建会话
curl -X POST localhost:8080/api/v1/sessions/{id}/messages \
     -H 'Content-Type: application/json' -d '{"content":"今天北京天气怎么样"}'   # 完整 ReAct 返回答复
curl localhost:8080/api/v1/tools                      # 列工具
open http://localhost:8080/admin                      # 管理平台（五页只读、无写按钮、错误展示 message）
open http://localhost:8080/swagger-ui                 # 接口文档（自动生成）
```

- **端到端验收（Demo 四/五 地基）**：Web 同步调用跑通（真模型：POST messages 拿到完整 ReAct 答复）+ 多端点联动（GET /profiles 拿 Agent 名 → POST /agents/{name}/invoke 无状态调用）
- **干净机器单 key 启动**：环境只配 DEEPSEEK_API_KEY，serve 正常起在 8080（课件坑的实机验证）
- **管理台只读核对**：五页渲染正常、无写按钮、错误时展示统一信封 message
