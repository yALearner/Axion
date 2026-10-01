# java-spring-init Skill 验证报告

> 验证时间:2026-08-26 ~ 2026-08-27
> 验证方式:在 Axion 项目(9 模块 Java 21 + Spring Boot 3.x)上按 SKILL.md 全流程真实执行
> 执行分支:`feat/engineering-foundation`(9 步 commit,未推送、未合并)
> 验证人:Claude Code(java-spring-init skill 的执行与验证)

---

## 结论

**skill 流程设计合理、可执行,但存在 3 处事实错误和若干未预警坑位,已全部修正并沉淀进 skill 自身。**
工程地基已在 `D:\myproject\Axion` 完整落地,分 9 步 commit。

---

## 一、执行结果(Definition of Done 核对)

| 检查项 | 结果 | 验证方式 |
|--------|------|---------|
| `mvn clean package` 出 fat JAR | ✅ 181MB JAR 构建并启动成功 | 实测 |
| 结构化 JSON 日志 + JSONL 文件 | ✅ `logs/axion.jsonl` 持续写入 | 启动后查文件 |
| `/actuator/health` `/actuator/prometheus` | ✅ UP + 指标(带 `application=axion` 标签) | curl 实测 |
| 虚拟线程 | ✅ 已存在(`spring.threads.virtual.enabled: true`),确认未动 | 核对 yaml |
| `/swagger-ui.html` + OpenAPI JSON | ✅ 200 / 200 | curl 实测 |
| ApiResponse + GlobalExceptionHandler | ✅ 404 返回 `{"errorCode":404,"message":"资源不存在: ..."}` 统一信封 | curl 实测 |
| Spotless / Enforcer / Error Prone | ✅ 全部生效 | 负面验证各自阻断构建 |
| 阿里 P3C | ✅ `ThreadPoolCreationRule` 真实命中 | 负面验证(Executors 建线程池) |
| Checkstyle | ✅ 0 violations | 全模块 verify |
| 安全四件套接入 | ⚠️ SpotBugs+FSB+PMD 已接入 verify;semgrep CI-only(本机无 Python);DepCheck 配置就位但**本地首跑因无 NVD key 未完成** | 部分实测 |
| CI 三 job + pre-commit | ✅ 配置就位,与既有 deploy.yml 共存 | 未实际运行(见遗留事项) |
| 敏感配置无明文 | ✅ 无新增密钥,全走 `${ENV_VAR}` 占位 | 核对 |
| 宪法 9 条无冲突 | ✅ 逐条核对结论已写入 skill 的 `references/constitution-checklist.md` | 核对 |

---

## 二、Skill 设计合理处(验证通过)

1. **「检测→生成→验证→分步 commit」结构有效**:9 步 commit 边界干净,任一步可独立回滚。
2. **「探测不硬编码」救了 3 次**:naive 探测 Maven Central 会拿到 Boot 4.2.0-M1(milestone)、
   logstash-logback-encoder 9.0(需 Jackson 3,与 Boot 3.5 不兼容)、springdoc 3.1.0(只配 Boot 4)
   ——兼容矩阵筛选全部命中。最终选型:**Boot 3.5.16 / springdoc 2.8.17 / encoder 8.1**。
3. **步骤 7 预警的 P3C 兼容风险真实存在**,按预案走主路径解决(钉 PMD 6.55 线 +
   负面验证确认规则在 JDK 21 上真实执行)。
4. **步骤 10 的负面验证要求是最大价值点**——Error Prone、P3C、Spotless 都靠它才确认
   「真的在跑」而非静默失效。
5. **幂等原则正确**:9 模块骨架、虚拟线程配置等已存在项原样保留,只增量补齐缺失/过时项。

---

## 三、发现的问题(已在 skill 中修正)

| # | 问题 | 严重度 | 处理 |
|---|------|--------|------|
| 1 | SKILL.md 写的 `rulesets/java/ali-pmd.xml` **在 p3c-pmd 2.1.1 中不存在**(1.x 聚合文件已拆成 9 个独立 ruleset) | 高(照抄必挂) | SKILL.md 步骤 7 已修正为 9 个 ruleset 清单 |
| 2 | 「NVD API key 提示必需性(无 key 限流 ~6 次/30 秒)」**已过时**:NVD 2025 年政策强制要求 key,无 key 直接报 `Invalid API Key, length of 0`;且空 key 同罪,会持久化进数据目录缓存 | 高(CI 必红) | SKILL.md 步骤 8 + `references/security.md` 已更新 |
| 3 | **Error Prone + NullAway 组合在原生 javac 21u 上不可行**:`-Xep*` 参数被 javac 解析期直接拒绝(实测 6 种配置组合全部失败),NullAway 无法激活 | 高(skill 未预警) | 已补充坑位与可用组合:`.mvn/jvm.config` + `-Xplugin:ErrorProne -Werror`(EP 默认检查全开);NullAway 暂由 SpotBugs 兜底,完整激活需 patched javac 或 error-prone-maven-plugin(不在 Central) |
| 4 | 引用的 6 个 `references/*.md` + 3 个 `templates/*.tpl` 全部标【待创建】不存在,执行者须现场创作 | 中 | 已全部补齐,6 条实测坑位沉淀进 `references/tool-versions.md` |

---

## 四、执行中额外发现并修复的项目问题

- **扫描范围 bug**:`@SpringBootApplication` 默认只扫 `com.axion.boot` 包,导致
  GlobalExceptionHandler 未被注册(冒烟测试抓出,404 返回的是 Boot 默认错误体而非统一信封)
  → 修复为 `scanBasePackages = "com.axion"`。
- **5 处真实依赖版本分裂**(snakeyaml、jsonschema-generator ×2、antlr4-runtime、commons-lang3)
  由 `requireUpperBoundDeps` 门禁抓出并钉上界;snakeyaml 同版本双路径误报(MENFORCER-336)
  以两处带注释 exclusion 处理。
- **本机环境变量 `NVD_API_KEY` 存在但为空**,是 Dependency-Check 反复失败的根因之一
  (dependency-check 13 把空 key 当非法 key)。
- **Error Prone 插件模式所需 JVM 参数必须放 `.mvn/jvm.config`**(JVM 启动即生效),
  compilerArgs 里的 `--add-exports` 顺序不对会导致插件静默加载失败。

---

## 五、执行 commit 清单(分支 `feat/engineering-foundation`)

| Commit | 内容 |
|--------|------|
| `d20353e` | chore: 升级 Spring Boot 3.3.5 → 3.5.16、springdoc 2.5.0 → 2.8.17 |
| `b547273` | feat: 结构化 JSON 日志 + Actuator/Prometheus 监控 |
| `1da6861` | feat: 统一响应信封与全局异常处理(API 规范地基) |
| `b38b609` | feat: 开发规范工具链(Spotless/Enforcer/Error Prone/Checkstyle/阿里 P3C) |
| `286bf15` | feat: 代码安全检查接入 verify 链(SpotBugs + FSB + Dependency-Check) |
| `9c2bb6d` | feat: CI 三 job 门禁 + CodeQL + pre-commit |
| `6c4d9ec` | fix: 启动模块扫描根扩大到 com.axion |
| `f449f39` | docs: 补全 skill 的 references 与 templates,修正验证发现的错误 |
| `fce0980` | fix: Dependency-Check NVD key 处理修正 |

---

## 六、遗留事项

> 状态更新于 2026-08-28,收尾执行记录见第七节。

1. **合并分支**:✅ 已完成——`merge --no-ff` 合入 main(merge commit `5fce01c`)并推送。
2. **NVD API key**:⚠️ 部分完成——用户已申请 key 并配置 GitHub secret;本地环境变量未配置
   (本地首跑仍待 key);CI DepCheck 步骤在 secret 生效后应转绿。
3. **CI 实测**:✅ 已实测——build/style 通过;CodeQL 通过;security 的 semgrep 步骤通过,
   DepCheck 步骤因 secret 配置时序失败(重触发验证中);另发现并修复 semgrep action 引用错误。
4. **未验证项**:
   - ✅ semgrep 本地验证——通过 uv(本机无 Python 的解法:`uv tool install semgrep`),
     60 条 p/java 规则扫 15 个 Java 文件,0 findings
   - ✅ prod profile 的 console JSON 输出——LogstashEncoder JSON 格式实测正常
   - ❌ NullAway 激活——最终结论:原生 javac 21u 拒绝 -Xep 参数;error-prone-javac 仅 javac 9 线
     (无法编译 Java 21);error-prone-maven-plugin 不存在于 Central/阿里云镜像。
     当前 EP(-Werror)+ SpotBugs 兜底即为最优可达配置(详见 tool-versions.md 坑位 2)
5. `docs/prompt/01.md` 是工作区原有改动,执行全程未触碰。
6. **Deploy to GitHub Pages 持续失败**:既有问题(2026-08-19 的旧运行同样失败,早于本次改动),
   失败步骤为 `actions/deploy-pages@v4`——常见原因是仓库 Settings 未启用 Pages 或发布源配置不符。

---

## 七、遗留事项收尾执行记录(2026-08-28)

| 动作 | 结果 |
|------|------|
| prod profile 冒烟 | ✅ console 为 Logstash JSON,health/swagger 正常 |
| semgrep 本地验证 | ✅ uv tool install semgrep 1.175.0,p/java 60 规则 0 findings |
| NullAway 再尝试 | ❌ 三通道逐一排除:原生 javac 拒 -Xep / error-prone-javac 仅 javac 9 / maven 插件不在任何可达仓库 |
| 分支合并推送 | ✅ `merge --no-ff` → main,SSH 推送 upstream(https 凭证属他人账号被拒) |
| CI 首轮实测 | ✅ build/style/CodeQL 绿;security 首轮因 semgrep action 引用错误失败 |
| semgrep action 修复 | ✅ `semgrep/semgrep@v1` → `returntocorp/semgrep-action@v1`(commit `5746ca0`) |
| CI 次轮实测 | ✅ semgrep 步骤通过;仅剩 DepCheck 步骤(等待 NVD_API_KEY secret) |
| NVD key | ⚠️ 用户已配 GitHub secret,待重触发验证;本地 env 未配,本地首跑仍待 |


---

## 附:验证期间的关键实测数据

| 实测项 | 结果 |
|--------|------|
| Boot 3.5.16 升级后全量编译 | ✅ 一次通过(Spring AI 1.0.0-M4 兼容) |
| P3C 负面验证 | `Executors.newFixedThreadPool(1)` → `ThreadPoolCreationRule` 阻断(连同 ClassMustHaveAuthorRule、CloseResource 等共 4 条) |
| Error Prone 负面验证 | `s == "literal"` → `[ReferenceEquality]` + `-Werror` 使构建失败 |
| Spotless 负面验证 | 4 空格缩进文件 → `spotless:check` 报格式违规 |
| 启动冒烟 | health UP、prometheus 有指标、swagger-ui 200、404 统一信封、JSONL 日志写入 |
| `mvn clean verify` 最终全量 | ✅ BUILD SUCCESS(全部门禁一次通过) |
