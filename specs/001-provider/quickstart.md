# Quickstart 验证指南: 001-provider

本指南是**验证/跑法说明**，不是实现文档。实现在 `tasks.md` 与实现阶段产出。

## 前置条件

1. 分支 `001-provider`，JDK 21，Maven 可用
2. 工程地基已就位（9 模块骨架、静态检查门禁）
3. （可选，冒烟用）`DEEPSEEK_API_KEY` 环境变量

## 日常验证（默认，秒级）

```bash
mvn test
```

预期：四个单测类全绿——
- `ProfileLoaderTest`：frontmatter 全字段解析、provider 引用校验、坏文件不阻断、`${ENV}` 占位解析
- `ProviderServiceTest`：双 provider 路由不串台、未知名抛异常、成败都落审计、自动执行关闭、无类型扫描
- `ToolSchemaAdapterTest`：schema 字段一一对齐、产物无执行逻辑
- `LlmCallRepositoryTest`：手工 schema.sql 建表可存可读，`success`/`error_message` 列真实存在

## 完成定义

```bash
mvn clean verify     # 全绿（含静态检查门禁）才算实现完成
```

## 集成冒烟（手动，验真连通）

```bash
# bash: DEEPSEEK_API_KEY=xxx mvn -pl axion-provider -am test -Dtest.groups=integration -Dtest.excludedGroups=
# PowerShell（逐行执行）:
#   $env:DEEPSEEK_API_KEY = "xxx"
#   mvn -pl axion-provider -am test "-Dtest.groups=integration" "-Dtest.excludedGroups="
```

预期：`ProviderSmokeIT` 真调一次模型，拿到非空响应，且 `llm_calls` 新增一条 `success=true` 记录。

## 人工核对

1. 打开 `.axion/axion.db` 核对 `llm_calls`：provider/model/token 与 API 实际返回一致
2. `grep -r "sk-"`（或所用 key 前缀）在代码与配置中无明文命中
3. 显式映射实现 review：无扫描容器 `ChatModel` 集合的代码路径
4. 需求文档 §13 要求两家真实跑通——无 KIMI key 时先跑通 DeepSeek 并记录待办
