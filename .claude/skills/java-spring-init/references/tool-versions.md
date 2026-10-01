# 工具版本参考（探测方法 + 历史记录）

> 版本号不固化进 SKILL.md——实施时按步骤 2 探测 Maven Central metadata 取最新稳定版。
> 下表仅作参考与坑位记录，标注记录日期。

## 探测方法

```bash
# 取最新稳定版（注意：<latest> 可能指向 milestone/大版本，需按兼容线过滤）
curl -s "https://repo1.maven.org/maven2/<groupId路径>/<artifactId>/maven-metadata.xml" \
  | grep -oE "<(latest|release)>[^<]+"

# 过滤特定版本线，如 Boot 3.5.x：
curl -s ".../spring-boot-starter-parent/maven-metadata.xml" \
  | grep -oE "<version>3\.5\.[0-9]+</version>" | tail -3
```

## 历史记录（2026-08-26 探测，Axion 实施）

| 组件 | 选定版本 | 线 | 备注 |
|------|---------|-----|------|
| spring-boot-starter-parent | 3.5.16 | 3.5.x | 3.3.x 已停止维护；4.x 尚在 milestone |
| springdoc-openapi-starter-webmvc-ui | 2.8.17 | 2.8.x | **3.x 只配 Boot 4**（兼容矩阵） |
| logstash-logback-encoder | 8.1 | 8.x | **9.x 需 Jackson 3，与 Boot 3.5（Jackson 2）不兼容** |
| spotless-maven-plugin | 3.10.0 | — | googleJavaFormat 独立指定版本 |
| google-java-format | 1.36.1 | — | GOOGLE 风格（2 空格） |
| maven-checkstyle-plugin | 3.6.0 | — | checkstyle 版本单独钉 10.26.1 |
| checkstyle | 10.26.1 | 10.x | canonical google_checks.xml 从 checkstyle jar 提取（见坑位） |
| maven-enforcer-plugin | 3.6.3 | — | |
| maven-pmd-plugin | 3.21.2 | PMD 6.55 | **必须 PMD 6 线：p3c-pmd 官方版基于 PMD 6**（PMD 7 加载 P3C ruleset 会失败） |
| p3c-pmd | 2.1.1 | — | 见坑位：无聚合版 ali-pmd.xml |
| spotbugs-maven-plugin | 4.10.4.0 | — | |
| findsecbugs-plugin | 1.14.0 | — | |
| dependency-check-maven | 13.0.0 | — | 见坑位：空 NVD_API_KEY |
| error_prone_core | 2.50.0 | — | javac plugin 模式（见坑位） |
| nullaway | 0.14.0 | — | 未激活（见坑位） |

## 坑位（实测，实施时绕开）

1. **Boot BOM 版本分裂**：spring-ai 1.0.0-M4 的模块 parent 链自带旧版 Boot BOM 托管版本
   （snakeyaml/commons-lang3/jsonschema-generator/antlr4-runtime 等），与 Boot 3.5.16 冲突。
   → 开启 `requireUpperBoundDeps` 后逐个钉上界 + 同版本双路径误报（MENFORCER-336）用带注释 exclusion。
2. **Error Prone 在原生 javac 21u 上**：`-Xep*` 配置参数被 javac 解析期直接拒绝（插件已注册也无效），
   NullAway（依赖 `-XepOpt:NullAway:AnnotatedPackages`）无法激活。
   → 工作组合：`.mvn/jvm.config` 放 add-exports/add-opens（JVM 启动即生效，顺序无关）+ 
   `-XDcompilePolicy=simple -XDaddTypeAnnotationsToSymbol=true --should-stop=ifError=FLOW -Xplugin:ErrorProne -Werror`。
   完整 EP 配置能力需 patched javac（error-prone-javac）或 error-prone-maven-plugin（不在 Central）。
3. **p3c-pmd 2.1.1 没有 `rulesets/java/ali-pmd.xml`**（1.x 的聚合文件已拆），必须逐个列出 9 个独立 ruleset。
4. **maven-pmd-plugin 3.x 从项目 classpath 解析 rulesets**，p3c-pmd 需同时作为插件依赖（规则类）
   与项目依赖（ruleset 文件）；SKILL.md 步骤 7 的 ruleset 清单按本表执行。
5. **dependency-check 13 起 NVD API key 必需**（NVD 2025 年政策，无 key 直接报
   `Invalid API Key, length of 0`，空 key 同罪）。pom 里不要写
   `<nvdApiKey>${env.NVD_API_KEY}</nvdApiKey>`；CI 空 secret 时必须 `unset NVD_API_KEY`
   （不能只省略 -D 参数）；本地无 key 则首跑无法完成，需先申请免费 key。
6. **google_checks.xml 获取**：raw.githubusercontent 按 tag 拉取可能 404，直接下载
   `checkstyle-<ver>.jar` 从 jar 根目录提取（unzip -o -j），保证与钉住的 checkstyle 版本完全一致。
