---
name: java-spring-init
description: >-
  为 Java 21 + Spring Boot 3.x + Maven 项目（单模块 / 多模块 / Axion 式 9 模块）初始化工程地基：
  Maven 骨架、结构化 JSON 日志（Logback）、监控（Actuator/Micrometer）、Spring MVC + 虚拟线程、
  springdoc OpenAPI + 统一响应体与全局异常/错误码、开发规范（Spotless Google 格式 +
  阿里编码规约 P3C + Checkstyle 兜底）、代码安全检查（从严：SpotBugs + Find Security Bugs +
  PMD + semgrep + OWASP Dependency-Check 全部阻断）、
  GitHub Actions CI（build/style/security 三 job，均须通过）与 pre-commit。
  当用户要「初始化项目 / 搭工程骨架 / 起脚手架 / 加日志监控 / 加开发规范 / 加代码安全检查」时使用。
  执行时先探测环境与项目现状，逐块「检测→生成→验证」，每步完成后 git commit，并核对项目
  CLAUDE.md/宪法约束（如 Axion 9 条原则）。
---

# Java/Spring 项目工程地基初始化

把"工程地基"一次性、标准化地装好——**业务逻辑不在本 skill 范围内**（业务走 Spec-Kit user story）。

## 什么时候用

- 新建 Java/Spring Boot 仓库、或给空仓库起工程骨架时
- 要给项目补齐日志 / 监控 / API 规范 / 开发规范 / 安全检查时
- 任何 JDK 21 + Spring Boot 3.x 的 Maven 项目（含 Axion 式 9 模块），想一次到位装好工程地基时

## 不做什么（边界）

- 不实现业务功能（Axion 的 Provider/ReAct/Memory/Tool/Web 等走 Spec-Kit 拆解）
- 不硬编码任何密钥 / token / API key——一律环境变量占位（`${ENV_VAR}`）
- 不替换已存在的业务代码与配置；只增量补齐缺失 / 过时项（幂等原则）
- 不固化工具版本号——只固化**探测方法**（见步骤 2）
- 不复制项目宪法全文——引用并逐条核对，避免双源漂移

## 三层分工（本 skill 只做第一层）

| 层 | 职责 | 载体 |
|----|------|------|
| 生成与指导层 | 探测现状 → 生成缺失 → 当场验证 | 本 skill |
| 本地即时强制（可选） | 每次写文件自动格式化/检查 | Claude Code hooks（本 skill 只提示，不自动安装） |
| 构建/CI 强制 | 规范门禁、漏洞拦截 | 本 skill 生成的 Maven 插件配置 + CI workflow + pre-commit |

---

## 执行总流程

```
步骤 0 确认参数 → 步骤 1 骨架 → 步骤 2 版本探测 → 步骤 3~9 逐块[检测现状→生成→验证]
→ 步骤 10 全量验证（含负面验证）→ DoD 检查清单
```

- 每步完成后 `git commit`（一次一步，回滚清晰）
- 每块验证失败先修正，再进下一块；与项目 CLAUDE.md 冲突 → 停下报告，不静默覆盖
- 若项目根存在 `CLAUDE.md` / `.specify/memory/constitution.md`：先读其"不可违背原则"章节，
  逐条核对后续生成内容（Axion 9 条核对清单见 `references/constitution-checklist.md`）

---

### 步骤 0：确认参数

向用户确认（有默认值）：`groupId`、根 `artifactId`、模块清单（多模块时；Axion 默认 9 模块：
storage/core/provider/memory/tool/channel-cli/web/cli/boot）、启动模块与端口（默认 8080）、
JDK（21）、仓库可见性（public/private，决定 SAST 选型）、CI 平台（默认 GitHub Actions）。
同时探测环境：`java -version`、`mvn -version`（PATH 未配置时定位安装路径并 export 注入当前会话）、
`~/.m2/settings.xml` 镜像范围（阿里云镜像只代理 central 时其他仓库直连）。

### 步骤 1：Maven 骨架（仅当缺失）

- 父 `pom.xml`（packaging=pom）+ 子模块；启动模块含 `main`，打 fat JAR
- 多模块场景（Axion）：依赖方向单向——storage ← core ← 能力层（provider/memory/tool）←
  channel-cli/web ← cli ← boot（以项目 CLAUDE.md 定义为准）
- 版本统一在父 pom `dependencyManagement` / `pluginManagement`（多模块下插件统一配置放 pluginManagement）

### 步骤 2：依赖版本管理（探测，不硬编码）

- 全部工具版本先查 Maven Central metadata
  （`https://repo1.maven.org/maven2/<groupId路径>/<artifactId>/maven-metadata.xml`）取最新稳定版
- Boot↔Spring AI↔springdoc 等**兼容矩阵**查官方文档后再锁定
- 历史已知版本表（仅参考，标注记录日期）见 `references/tool-versions.md`
- 注意：Spring AI 若按项目宪法要求用 `@Tool` schema 生成，需 GA 线（1.0.x/1.1.x）；
  OpenAI 兼容 Provider 走官方 `spring-ai-starter-model-openai`

### 步骤 3：日志（结构化 JSON）

- 检测现状：`logback-spring.xml` / 日志依赖是否存在
- 生成（长模板 `templates/logback-spring.xml.tpl`）：
  - 开发/CLI 环境：彩色 console pattern
  - 生产（profile=prod）：JSON 输出（`LogstashEncoder`）+ MDC `traceId`/`session_id`；
    JSON 滚动文件 `logs/<app>.jsonl` 常开
  - 统一经 SLF4J，禁止 `System.out`
- 约定固化（`references/logging.md`）：MDC 会话级 + `StructuredArguments.kv()` 事件级；
  encoder 版本↔Logback 版本兼容规则（8.x↔1.5.x；9.x 需 Jackson 3 不可用）；虚拟线程 MDC 不跨线程继承
- 验证：启动日志检查

### 步骤 4：监控（Actuator + Micrometer + Prometheus）

- 检测现状：actuator 是否已引入
- 依赖：`spring-boot-starter-actuator` + `micrometer-registry-prometheus`（runtime scope）
- 配置：
  ```yaml
  management:
    endpoints.web.exposure.include: health,info,prometheus,metrics
    endpoint.health.probes.enabled: true
    metrics.tags.application: <app-name>
  ```
- 决策分支：纯 CLI 项目（当前无 Web 启动模块）→ 仅 Actuator + health，Prometheus registry 推迟到
  Web 模块落地；有 Web 启动模块 → 全量一次到位
- 验证：`/actuator/health`（UP）、`/actuator/prometheus`（有指标）

### 步骤 5：HTTP Server（Spring MVC + 虚拟线程）

- 依赖：`spring-boot-starter-web`
- 配置：
  ```yaml
  server.port: 8080
  spring.threads.virtual.enabled: true   # JDK 21 虚拟线程（宪法：同步模型 + 虚拟线程承载并发）
  ```

### 步骤 6：API 规范（OpenAPI + 统一响应 / 错误码）

- 依赖：springdoc（版本按 Boot 兼容矩阵探测；2.8.x 线配 Boot 3.5.x，3.x 只配 Boot 4）
- 生成：
  - `ApiResponse<T>`：统一响应体（`code` / `message` / `data` / `timestamp`——若项目已有定义则沿用）
  - `GlobalExceptionHandler`（`@RestControllerAdvice`）：异常 → 标准 JSON 错误
    （`errorCode` / `message` / `timestamp`），覆盖 400 / 404 / 500 / 503
  - REST 约定（`references/api-conventions.md`）：资源名词复数、`/api/v1` 前缀、合理状态码、
    `@Operation`/`@Schema`/`@Parameter` 注解
  - OpenAPI 快照门禁：CI 中 `git diff --exit-code` 校验生成的 openapi 文件
- 验证：MockMvc 冒烟 + 打开 `/swagger-ui.html` + 抽查 openapi JSON

### 步骤 7：开发规范（Google 格式 + 阿里编码规约 + Checkstyle 兜底，三层互补）

职责分开，互不冲突：**Google 管「长什么样」（格式，自动修）；阿里管「怎么写才对」（规约，核心卖点）；
Checkstyle 兜底**。

- **格式层**：Spotless + google-java-format（`reorderImports(true)` + importOrder + removeUnusedImports），
  绑定 `spotless:check`（模板 `templates/spotless-config.xml.tpl`）
- **构建约束层**：Maven Enforcer——`requireJavaVersion [21,)`、`requireMavenVersion`、
  `requireUpperBoundDeps`（Boot BOM 场景避免 dependencyConvergence 误报）；bannedDependencies（如 `log4j:log4j`）
- **静态检查层**：Error Prone + NullAway（pluginManagement 统一，`AnnotatedPackages` 参数化为项目根包名）
  - ⚠️ **原生 javac 21u 坑位**：`-Xep*` 配置参数会被 javac 解析期直接拒绝（`-Xplugin` 已注册也无效），
    NullAway（依赖 `-XepOpt:NullAway:AnnotatedPackages`）在原生 javac 上无法激活。
    可用组合：`.mvn/jvm.config` 放 add-exports/add-opens + 编译参数
    `-XDcompilePolicy=simple -XDaddTypeAnnotationsToSymbol=true --should-stop=ifError=FLOW
    -Xplugin:ErrorProne -Werror`（EP 默认检查 + 警告升级错误）。
    完整 EP 配置能力需 patched javac 或 error-prone-maven-plugin（不在 Central）；
    空指针检查由步骤 8 的 SpotBugs 兜底。细节见 `references/tool-versions.md` 坑位 2
- **阿里编码规约层（P3C，核心卖点）**：maven-pmd-plugin 挂载 P3C ruleset，绑定 `pmd:check`；
  开发者本地装「阿里巴巴 Java 编码规约」IDE 插件即时提示
  - ⚠️ **兼容性风险（必须验证）**：p3c-pmd 官方版基于 PMD 6（2.1.1 对 PMD 6.15），与 PMD 7 /
    Java 21 运行时组合可能不兼容。maven-pmd-plugin 需钉 PMD 6 线（如 3.21.2 = PMD 6.55）。
    **p3c-pmd 2.1.1 没有聚合版 `ali-pmd.xml`**（1.x 已拆），必须逐个列出 9 个独立 ruleset
    （ali-comment/ali-concurrent/ali-constant/ali-exception/ali-flowcontrol/ali-naming/
    ali-oop/ali-other/ali-set），清单见 `references/alibaba-rules.md`。
    生成后**必须**在负面验证中确认 `pmd:check` 真正执行了阿里规则（而不是静默失效）——
    只看 BUILD SUCCESS 不够。若 PMD 6 在本机 JDK 21 无法运行：回退方案一 = PMD 7 +
    社区移植版 P3C ruleset；回退方案二 = 降为 `references/alibaba-rules.md` 参考层 +
    IDE 插件，并在报告中写明回退原因
- **兜底层**：Checkstyle `google_checks.xml` + 根目录 `.editorconfig`
- 冲突裁决：风格冲突以 google-java-format 为准（自动修、省争论）；规约冲突按阿里规约执行
- 验证：`mvn spotless:check`、`mvn enforcer:enforce`、`mvn pmd:check`、`mvn -q compile`

### 步骤 8：代码安全检查（从严——全部接入、全部阻断）

企业可审计底座定位，安全扫描**从严执行**，四件套必装：

- **SpotBugs + Find Security Bugs**（必装，接入 `mvn verify` 链）：`effort=Max`、`threshold=Low`，
  覆盖 OWASP Top 10（SQL 注入/XSS/路径穿越/弱加密/XXE/不安全反序列化）
- **PMD**（源码层规则，随步骤 7 的 P3C 一并执行）
- **SAST**：semgrep（`p/java` 规则集，免费）；public 仓库可加 CodeQL（GHAS 付费项）；
  不选 Snyk（免费额度易烧完）
- **SCA**：OWASP Dependency-Check——`failBuildOnCVSS=7` + suppression 文件
  （新增 suppression 必须注释理由）；**NVD API key 必需**（NVD 2025 年政策，无 key 直接失败，
  不只是限流——免费申请 nvd.nist.gov；空 key 同罪，CI 空 secret 必须 `unset NVD_API_KEY`；
  pom 里不要写 `<nvdApiKey>${env.NVD_API_KEY}</nvdApiKey>`）
- **阻断策略**：以上任一失败 → CI 红 → 禁止合并
- 验证：`mvn verify`（含 spotbugs/pmd）+ `mvn dependency-check:check -DskipTests` 首跑（耗时，提示可后台）
  + semgrep 本地跑

### 步骤 9：CI + pre-commit

- **pre-commit（本地）**：提交前跑 `mvn spotless:check`（或 `spotless:apply`）
- **CI（GitHub Actions，模板 `templates/ci-workflow.yml.tpl`）**，job 分离（慢检查不阻塞 PR）：
  - **build**：`setup-java@v6` temurin 21 + `cache: maven` + `mvn -B -ntp verify` + 测试报告 artifact——
    任一不过则红，禁止合并
  - **style**：`mvn spotless:check`（快速失败）
  - **security**：SpotBugs/PMD 随 verify 执行 + dependency-check + semgrep（独立 job，**设为 required**，任一失败禁止合并）
  - 与既有 workflow（如 Pages deploy）共存，不覆盖
- 验证：本地 `act` 或推分支观察 workflow

### 步骤 10：全量验证（含负面验证）

- `mvn clean verify` 全绿
- 启动模块起得来；`/actuator/health`（UP）、`/actuator/prometheus`（有指标）、`/swagger-ui.html`（可打开）
- **负面验证**：故意写一行不规范代码 → `spotless:check` 报错；故意引一个含 CVE 的旧依赖 → depcheck 报警

---

## 检查清单（Definition of Done）

- [ ] Maven 骨架（模块清单与参数确认）可 `mvn clean package` 出 fat JAR
- [ ] 结构化日志（prod 为 JSON，含 traceId/session_id），无 `System.out`
- [ ] `/actuator/health` `/info` `/prometheus` 可访问（CLI-only 项目按分支规则）
- [ ] 虚拟线程开启（`spring.threads.virtual.enabled=true`，如有 Web）
- [ ] springdoc：`/swagger-ui.html` 可打开，`ApiResponse` + `GlobalExceptionHandler` 就位
- [ ] Spotless（Google 格式）+ Enforcer + Error Prone/NullAway 生效；**阿里 P3C 规约（`pmd:check`）生效**（若回退须在报告中说明原因）；Checkstyle 兜底就位；冲突以 GJF 为准
- [ ] **安全四件套从严**：SpotBugs + Find Security Bugs + PMD + semgrep + OWASP Dependency-Check 全部接入 CI，任一失败禁止合并
- [ ] pre-commit + CI 跑通（build/style/security 均 required）
- [ ] 敏感配置全用 `${ENV_VAR}` 占位，无明文密钥
- [ ] 项目宪法逐条核对无冲突（存在 CLAUDE.md 时）

---

## 与 constitution / Spec-Kit 的分工

- **本 skill**：把工程地基"装上"（一次性、可复用、跨模块）
- **constitution**：把硬约束"钉死"（JDK 21、Google 格式 + 阿里规约、必须过安全扫描（从严）、
  Spring AI 只用协议转换 + `@Tool` schema……），AI 每次遵守；本 skill 执行时逐条核对
- **CI + pre-commit**：把检查"强制执行"（机器把关，不靠人自觉）
- **Spec-Kit user story**：地基装好后，业务功能按 user story 逐个开发

## 目录规划（本 skill 内）

```text
java-spring-init/
├── SKILL.md                          # 本文件
├── references/
│   ├── tool-versions.md              # 历史版本表 + 探测方法 + 实测坑位（标注记录日期）
│   ├── logging.md                    # Logback/encoder 兼容矩阵与坑位
│   ├── api-conventions.md            # REST 命名/错误信封/springdoc 约定
│   ├── security.md                   # SCA/SAST 决策细节、密钥规范、NVD key 坑位
│   ├── constitution-checklist.md     # 宪法对齐核对清单（Axion 9 条示例）
│   └── alibaba-rules.md              # 阿里手册增量规则（P3C 规则集参考与回退兜底）
└── templates/
    ├── logback-spring.xml.tpl
    ├── spotless-config.xml.tpl
    └── ci-workflow.yml.tpl
```

> 版本号、插件坐标以实施时探测结果为准；本 skill 给的是流程与配置骨架，不锁死具体版本。
