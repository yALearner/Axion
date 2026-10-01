<script setup>
import { computed } from 'vue'
import { useData, withBase } from 'vitepress'

const { lang } = useData()
const isZh = computed(() => lang.value === 'zh-CN')
const t = (zh, en) => isZh.value ? zh : en

const capabilities = computed(() => [
  {
    icon: '🔌',
    title: t('多 Provider 对接', 'Multi-Provider LLM'),
    subtitle: t('显式映射 · 运行时切换 · 不锁云', 'Explicit mapping · runtime switch · no lock-in'),
    code: `# AGENT.md — 声明式配置 Provider
provider:
  name: deepseek          # 显式映射 key
  model: deepseek-chat
  temperature: 0.7
  api_key: \${DEEPSEEK_API_KEY}   # 环境变量注入

# 切换模型 = 改一行配置
provider:
  name: qwen
  model: qwen-max
  api_key: \${QWEN_API_KEY}`,
  },
  {
    icon: '🧠',
    title: t('自实现 ReAct Loop', 'Self-implemented ReAct Loop'),
    subtitle: t('思考→行动→观察→循环 · 完全自主可控', 'Think→Act→Observe→Loop · fully controllable'),
    code: `// ReActLoop — Axion 自己实现，不依赖框架
while (iteration < maxIterations) {
    response = providerService.call(messages);
    if (!response.hasToolCalls()) return response;
    for (toolCall : response.toolCalls()) {
        sandbox.enforce(toolCall.action());
        result = toolExecutor.execute(toolCall);
        toolInvocationRepo.save(result);  // 审计写入
    }
    messages.append(results);
}`,
  },
  {
    icon: '📝',
    title: t('三层记忆系统', 'Three-tier Memory'),
    subtitle: t('会话记忆 · 长期记忆 · 可插拔后端', 'Session · Long-term · Pluggable backends'),
    code: `# 会话记忆 — 最近 max_history_turns 轮
# 长期记忆 — MEMORY.md 文件存储
# Agent 通过 save_memory Tool 写入

> save_memory "用户偏好：每天早上 8 点推送科技新闻"
# → 写入 .axion/memory/MEMORY.md 核心区

> recall_memory "科技新闻偏好"
# → 关键词检索 MEMORY.md

# 三档后端：Markdown / SQLite / Mem0
# 换后端 = 改一行 memory.backend 配置`,
  },
  {
    icon: '🔧',
    title: t('Plugin Tool 体系', 'Plugin Tool System'),
    subtitle: t('9 个内置 Tool · MCP · @Tool 注解 · 沙箱', '9 built-in Tools · MCP · @Tool · Sandbox'),
    code: `# 三档 Tool 扩展，按需选择

# 一档：零代码 — 复用社区 MCP server
mcp_servers:
  - github-mcp
  - postgres-mcp

# 二档：轻代码 — 任意语言写 MCP server
# JSON-RPC over stdio，Axion 作为 MCP Client

# 三档：重代码 — Java @Tool 注解
@Tool(description = "查询订单状态")
public String queryOrder(String orderId) { ... }`,
  },
  {
    icon: '🌐',
    title: t('三种触发源', 'Three Trigger Sources'),
    subtitle: t('CLI 人推 · REST API 人推 · Cron 钟推', 'CLI · REST API · Cron scheduler'),
    code: `# 触发源一：CLI（人推）
axion chat --profile ops-agent

# 触发源二：REST API（人推）
curl -X POST /api/v1/sessions/{id}/messages \\
  -d '{"content":"检查服务器状态"}'

# 触发源三：AgentScheduler（钟推）
schedules:
  - cron: "0 8 * * *"
    zone: Asia/Shanghai
    message: 生成今日天气和穿搭建议
# 三种源共用一个 ReActLoop 引擎`,
  },
])

const scenarios = computed(() => [
  {
    num: '01',
    title: t('银行合规助手', 'Banking Compliance Assistant'),
    desc: t('Agent 自动审查交易日志，发现异常实时推送告警。所有 Tool 调用和 LLM 请求完整记录在审计表，满足银保监会合规要求。', 'Agent auto-reviews transaction logs, pushes real-time alerts on anomalies. Full audit trail of every tool call and LLM request meets regulatory compliance.'),
  },
  {
    num: '02',
    title: t('政务审批自动化', 'Government Approval Automation'),
    desc: t('Agent 对接政务系统 API，自动核验材料、生成审批意见。私有部署确保公民数据不出政务云，所有操作可追溯。', 'Agent integrates with government APIs to auto-verify documents and generate approval drafts. Private deployment keeps citizen data on-premises with full traceability.'),
  },
  {
    num: '03',
    title: t('电信网络运维', 'Telecom Network Operations'),
    desc: t('运维 Agent 定时执行巡检脚本，发现故障自动创建工单并通知值班工程师。shell Tool 白名单确保只能执行授权的运维命令。', 'Ops Agent runs scheduled inspection scripts, auto-creates tickets and notifies on-call engineers. Shell whitelist ensures only authorized commands execute.'),
  },
  {
    num: '04',
    title: t('能源设备监控', 'Energy Equipment Monitoring'),
    desc: t('Agent 通过 HTTP Tool 拉取传感器数据，异常时调用 notify Tool 推送告警。本地 SQLite 存储所有历史记录，支持离线运行。', 'Agent pulls sensor data via HTTP Tool, pushes alerts via notify Tool on anomalies. Local SQLite stores full history; works offline.'),
  },
  {
    num: '05',
    title: t('医疗影像辅助诊断', 'Medical Imaging Assistance'),
    desc: t('Agent 调用影像分析 MCP Server 做初步筛查，结果写入院内系统。数据全程不出医院内网，满足 HIPAA/个人信息保护法要求。', 'Agent calls imaging analysis MCP Server for preliminary screening, writes results to hospital systems. Data never leaves the hospital network.'),
  },
  {
    num: '06',
    title: t('制造业质量检测', 'Manufacturing Quality Inspection'),
    desc: t('Agent 读取产线日志文件，调用质检模型 API，自动生成质检报告。定时任务到点自动运行，不需要人工发起。', 'Agent reads production line logs, calls QC model API, auto-generates inspection reports. Cron scheduler triggers runs automatically — no manual initiation.'),
  },
  {
    num: '07',
    title: t('金融风控 Agent', 'Financial Risk Control'),
    desc: t('Agent 通过 MCP Server 连接风控数据库，实时分析交易模式。Memory 模块跨会话记住风险偏好，自动升级可疑交易的审查级别。', 'Agent connects to risk databases via MCP Server, analyzes transaction patterns in real-time. Memory module retains risk preferences across sessions for adaptive review.'),
  },
  {
    num: '08',
    title: t('零售智能客服', 'Retail Intelligent Support'),
    desc: t('Agent 对接企业微信 Channel，处理退换货、订单查询等。save_memory 记住客户偏好，下次对话自动应用。所有对话可审计。', 'Agent connects to WeCom Channel, handles returns and order queries. save_memory remembers customer preferences for future conversations. Full audit trail.'),
  },
])

const integrations = computed(() => [
  {
    icon: '💻',
    title: t('CLI 命令行', 'CLI — Command Line'),
    desc: t('12 个 Picocli 子命令，交互式多轮对话。人推模式：开发者在终端里跟 Agent 对话，适合调试和 ad-hoc 任务。', '12 Picocli subcommands, interactive multi-turn chat. Human-pushed: developers converse with Agents in the terminal — perfect for debugging and ad-hoc tasks.'),
    codes: ['axion chat --profile ops-agent', 'axion serve --port 8080', 'axion profile list', 'axion tool list'],
  },
  {
    icon: '🔗',
    title: t('REST API', 'REST API'),
    desc: t('10 个核心端点，统一 ApiResponse 信封。人推模式：外部系统通过 HTTP 调用 Agent，适合集成到企业现有系统。', '10 core endpoints with unified ApiResponse envelope. Human-pushed: external systems call Agents via HTTP — integrates into existing enterprise systems.'),
    codes: ['POST /api/v1/sessions', 'POST /api/v1/sessions/{id}/messages', 'GET /api/v1/profiles', 'POST /api/v1/agents/{name}/invoke'],
  },
  {
    icon: '⏰',
    title: t('定时触发', 'AgentScheduler — Cron'),
    desc: t('钟推模式：Agent 按 cron 表达式到点自动运行。无需人工发起，适合日报生成、定时巡检、数据同步等周期性任务。', 'Clock-pushed: Agents auto-run on cron schedules. No human initiation needed — ideal for daily reports, scheduled inspections, and data sync tasks.'),
    codes: ['schedules:', '  - cron: "0 8 * * *"', '    zone: Asia/Shanghai', '    message: 生成今日天气和穿搭建议'],
  },
])
</script>

<template>
  <div class="axion-page">

    <!-- ══════════ HERO ══════════ -->
    <section class="axion-hero">
      <div class="axion-hero-inner">
        <div class="axion-badge">
          <span class="axion-badge-dot"></span>
          {{ t('Agent 运行时底座 · 私有部署 · 完全可审计', 'Agent Runtime OS · Private Deployment · Fully Auditable') }}
        </div>

        <h1 class="axion-title">
          <span class="axion-title-name">Axion</span>
        </h1>

        <p class="axion-title-sub">{{ t('Java 原生的企业级 Agent 统一底座', 'The Java-native enterprise Agent OS') }}</p>

        <p class="axion-hero-desc">
          {{ t('Axion 是一个开源的 Agent 运行时。装在自己的服务器或 K8s 上，就能配置、运行、监控多个 AI Agent。数据完全留在企业基础设施，所有操作可审计，不绑定任何云生态。', 'Axion is an open-source Agent runtime. Deploy on your own servers or K8s to configure, run, and monitor multiple AI Agents. All data stays on your infrastructure, every action is auditable, and there\'s zero cloud lock-in.') }}
        </p>

        <div class="axion-hero-actions">
          <a class="axion-btn-primary" :href="withBase(t('/zh/docs/quick-start', '/docs/quick-start'))">
            {{ t('快速开始', 'Get Started') }} →
          </a>
          <a class="axion-btn-ghost" :href="withBase(t('/zh/docs/architecture', '/docs/architecture'))">
            {{ t('系统架构', 'Architecture') }}
          </a>
          <a class="axion-btn-ghost" href="https://github.com/yALearner/Axion" target="_blank" rel="noopener">
            GitHub
          </a>
        </div>

        <div class="axion-hero-note">
          {{ t('Java 21 · Spring Boot 3.x · Spring AI · Picocli · SQLite · 一个 fat JAR 就跑起来', 'Java 21 · Spring Boot 3.x · Spring AI · Picocli · SQLite · Single fat JAR deployment') }}
        </div>
      </div>
    </section>

    <!-- ══════════ PROBLEM ══════════ -->
    <section class="axion-section">
      <div class="axion-section-inner">
        <div class="axion-problem">
          <div class="axion-problem-text">
            <h2 class="axion-section-title">{{ t('企业落地 Agent 的三个困境', 'Three Challenges of Enterprise Agent Adoption') }}</h2>
            <p>{{ t('今天如果你想在企业里落地 AI Agent，你会撞上这三个问题。', 'When you try to bring AI Agents into your enterprise, you hit these three walls.') }}</p>
            <div class="axion-problem-item">
              <strong>{{ t('① 数据不能出国，SaaS 过不了合规', '① Data sovereignty — SaaS fails compliance') }}</strong>
              {{ t('银行、政府、医疗等行业的核心数据严禁出企业内网。Coze、扣子等 SaaS 平台再好用，合规这关过不去。', 'Core data in banking, government, and healthcare must never leave the intranet. No matter how good SaaS platforms are, they fail the compliance audit.') }}
            </div>
            <div class="axion-problem-item">
              <strong>{{ t('② 开源 Agent 项目跟企业 Java 体系有接缝', '② Open-source Agent projects don\'t fit Java ecosystems') }}</strong>
              {{ t('OpenClaw（Node.js）、Hermes Agent（Python）——社区最活跃的 Agent 项目都不是 Java 的。企业 Java 团队要引入 Agent 能力，要么学新语言，要么自己造。', 'OpenClaw (Node.js), Hermes Agent (Python) — the most active Agent projects aren\'t Java. Enterprise Java teams face a choice: learn a new stack or build from scratch.') }}
            </div>
            <div class="axion-problem-item">
              <strong>{{ t('③ 框架只管 LLM 调用，治理层完全是空白', '③ Frameworks handle LLM calls — governance is a void') }}</strong>
              {{ t('Spring AI、LangChain 帮你调 LLM，但 Agent 常驻运行后的会话管理、Tool 审计、沙箱隔离、多租户 RBAC 全要自己写。每个团队都在重复造轮子。', 'Spring AI and LangChain handle LLM calls, but session management, tool auditing, sandbox isolation, and multi-tenant RBAC are left to you. Every team rebuilds the same plumbing.') }}
            </div>
            <p class="axion-solution-line">{{ t('Axion 专门解决这三个问题：一个 Java 原生、开箱即用、让 Agent 能常驻、可治理、可审计地跑起来的底座。', 'Axion solves exactly these three problems: a Java-native, turnkey runtime where Agents run persistently, governed, and fully auditable.') }}</p>
          </div>
          <div class="axion-problem-compare">
            <div class="axion-compare-item axion-compare-bad">
              <div class="axion-compare-label">{{ t('今天的做法', 'Today') }}</div>
              <div class="axion-compare-rows">
                <div class="axion-compare-row">
                  <span class="axion-compare-icon">✗</span>
                  <span>{{ t('SaaS 平台 → 数据出企业，合规失败', 'SaaS platforms → data leaves enterprise') }}</span>
                </div>
                <div class="axion-compare-row">
                  <span class="axion-compare-icon">✗</span>
                  <span>{{ t('Node.js/Python Agent → 跟 Java 体系两层皮', 'Node.js/Python → doesn\'t fit Java ecosystem') }}</span>
                </div>
                <div class="axion-compare-row">
                  <span class="axion-compare-icon">✗</span>
                  <span>{{ t('Spring AI + 自写治理 → 每个团队重复造轮子', 'Spring AI + DIY governance → reinventing the wheel') }}</span>
                </div>
                <div class="axion-compare-row">
                  <span class="axion-compare-icon">✗</span>
                  <span>{{ t('审计缺失 → 出问题无法追溯', 'No audit trail → impossible to trace issues') }}</span>
                </div>
              </div>
            </div>
            <div class="axion-compare-item axion-compare-good">
              <div class="axion-compare-label">Axion</div>
              <div class="axion-compare-rows">
                <div class="axion-compare-row">
                  <span class="axion-compare-icon axion-icon-ok">✓</span>
                  <span>{{ t('私有部署 → 数据完全留在企业内网', 'Private deployment → data stays on-premises') }}</span>
                </div>
                <div class="axion-compare-row">
                  <span class="axion-compare-icon axion-icon-ok">✓</span>
                  <span>{{ t('Java 21 + Spring Boot 3.x → 融入企业现有运维体系', 'Java 21 + Spring Boot 3.x → fits your ops stack') }}</span>
                </div>
                <div class="axion-compare-row">
                  <span class="axion-compare-icon axion-icon-ok">✓</span>
                  <span>{{ t('开箱即用 → ReAct + Memory + Tool + 审计全内置', 'Turnkey → ReAct + Memory + Tool + Audit built in') }}</span>
                </div>
                <div class="axion-compare-row">
                  <span class="axion-compare-icon axion-icon-ok">✓</span>
                  <span>{{ t('审计 Day One → tool_invocations + llm_calls 从第一天写入', 'Audit Day One → every call recorded from day one') }}</span>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ══════════ ARCHITECTURE DIAGRAM ══════════ -->
    <section class="axion-section axion-flow-section">
      <div class="axion-section-inner">
        <img :src="withBase('/diagram-architecture.svg')" alt="Axion Architecture" class="axion-flow-img" />
      </div>
    </section>

    <!-- ══════════ CORE CAPABILITIES ══════════ -->
    <section class="axion-section axion-primitives-section">
      <div class="axion-section-inner axion-primitives-inner">
        <div class="axion-section-header">
          <div class="axion-section-tag">{{ t('五大核心能力', 'Five Core Capabilities') }}</div>
          <h2 class="axion-section-title">{{ t('完整 Agent 运行时，开箱即用', 'A complete Agent runtime, out of the box') }}</h2>
        </div>
        <div class="axion-primitives">
          <div v-for="p in capabilities" :key="p.title" class="axion-primitive">
            <div class="axion-primitive-header">
              <span class="axion-primitive-icon">{{ p.icon }}</span>
              <div>
                <h3 class="axion-primitive-title">{{ p.title }}</h3>
                <p class="axion-primitive-subtitle">{{ p.subtitle }}</p>
              </div>
            </div>
            <pre class="axion-code"><code>{{ p.code }}</code></pre>
          </div>
        </div>
      </div>
    </section>

    <!-- ══════════ SCENARIOS ══════════ -->
    <section class="axion-section">
      <div class="axion-section-inner">
        <div class="axion-section-header">
          <div class="axion-section-tag">{{ t('企业场景', 'Enterprise Scenarios') }}</div>
          <h2 class="axion-section-title">{{ t('八个真实企业场景', 'Eight real-world enterprise use cases') }}</h2>
        </div>
        <div class="axion-scenarios">
          <div v-for="s in scenarios" :key="s.num" class="axion-scenario">
            <div class="axion-scenario-num">{{ s.num }}</div>
            <div>
              <h3 class="axion-scenario-title">{{ s.title }}</h3>
              <p class="axion-scenario-desc">{{ s.desc }}</p>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ══════════ INTEGRATION ══════════ -->
    <section class="axion-section axion-sdk-section">
      <div class="axion-section-inner">
        <div class="axion-section-header">
          <div class="axion-section-tag">{{ t('三种触发源', 'Three Trigger Sources') }}</div>
          <h2 class="axion-section-title">{{ t('人推 + 钟推，共用一个引擎', 'Human-pushed + Clock-pushed, one engine') }}</h2>
        </div>
        <div class="axion-sdk-cards">
          <div v-for="item in integrations" :key="item.title" class="axion-sdk-card">
            <div class="axion-sdk-card-icon">{{ item.icon }}</div>
            <h3 class="axion-sdk-card-title">{{ item.title }}</h3>
            <p class="axion-sdk-card-desc">{{ item.desc }}</p>
            <div class="axion-sdk-installs">
              <code v-for="c in item.codes" :key="c">{{ c }}</code>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ══════════ MODULE OVERVIEW ══════════ -->
    <section class="axion-section">
      <div class="axion-section-inner">
        <div class="axion-section-header">
          <div class="axion-section-tag">{{ t('工程结构', 'Project Structure') }}</div>
          <h2 class="axion-section-title">{{ t('9 个 Maven 模块，职责清晰', '9 Maven modules, clean separation of concerns') }}</h2>
        </div>
        <div class="axion-module-grid">
          <div class="axion-module-group">
            <div class="axion-module-group-label">{{ t('内核层', 'Kernel') }}</div>
            <div class="axion-module-row">
              <code class="axion-module-name">axion-core</code>
              <span class="axion-module-desc">{{ t('ReActLoop · PromptBuilder · ToolExecutor · AgentService', 'ReActLoop · PromptBuilder · ToolExecutor · AgentService') }}</span>
            </div>
            <div class="axion-module-row">
              <code class="axion-module-name">axion-storage</code>
              <span class="axion-module-desc">{{ t('SQLite · SessionRepository · 审计表写入', 'SQLite · SessionRepository · audit table writes') }}</span>
            </div>
          </div>
          <div class="axion-module-group">
            <div class="axion-module-group-label">{{ t('能力层', 'Capabilities') }}</div>
            <div class="axion-module-row">
              <code class="axion-module-name">axion-provider</code>
              <span class="axion-module-desc">{{ t('多 LLM Provider 显式映射', 'Multi-provider explicit mapping') }}</span>
            </div>
            <div class="axion-module-row">
              <code class="axion-module-name">axion-memory</code>
              <span class="axion-module-desc">{{ t('MemoryService · LongTermMemory 可插拔后端', 'MemoryService · pluggable LongTermMemory backends') }}</span>
            </div>
            <div class="axion-module-row">
              <code class="axion-module-name">axion-tool</code>
              <span class="axion-module-desc">{{ t('9 个内置 Tool · MCP Client · Sandbox · Notify', '9 built-in Tools · MCP Client · Sandbox · Notify') }}</span>
            </div>
          </div>
          <div class="axion-module-group">
            <div class="axion-module-group-label">{{ t('接入层', 'Channels') }}</div>
            <div class="axion-module-row">
              <code class="axion-module-name">axion-channel-cli</code>
              <span class="axion-module-desc">{{ t('交互式 CLI 对话 Channel', 'Interactive CLI chat Channel') }}</span>
            </div>
            <div class="axion-module-row">
              <code class="axion-module-name">axion-web</code>
              <span class="axion-module-desc">{{ t('REST API · 10 个端点 · OpenAPI 文档', 'REST API · 10 endpoints · OpenAPI docs') }}</span>
            </div>
          </div>
          <div class="axion-module-group">
            <div class="axion-module-group-label">{{ t('启动层', 'Boot') }}</div>
            <div class="axion-module-row">
              <code class="axion-module-name">axion-cli</code>
              <span class="axion-module-desc">{{ t('Picocli 12 个子命令 · ConfigLoader', 'Picocli 12 subcommands · ConfigLoader') }}</span>
            </div>
            <div class="axion-module-row">
              <code class="axion-module-name">axion-boot</code>
              <span class="axion-module-desc">{{ t('Spring Boot 启动 · 自动配置 · 依赖聚合', 'Spring Boot entry · auto-config · dependency aggregation') }}</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ══════════ CTA ══════════ -->
    <section class="axion-section axion-cta-section">
      <div class="axion-section-inner">
        <div class="axion-cta">
          <h2 class="axion-cta-title">{{ t('开始构建', 'Start Building') }}</h2>
          <p class="axion-cta-desc">{{ t('一个 fat JAR，一个目录，一个 Agent 就起来了。不需要 PostgreSQL，不需要 Redis。', 'One fat JAR, one directory, one Agent running. No PostgreSQL, no Redis required.') }}</p>
          <pre class="axion-code axion-cta-code"><code># 克隆仓库
git clone https://github.com/yALearner/Axion.git
cd axion

# 编译打包
mvn clean package -DskipTests

# 初始化工作区
java -jar axion-boot/target/axion-boot-*.jar init

# 配置 API Key
export DEEPSEEK_API_KEY=sk-your-key-here

# 启动交互对话
java -jar axion-boot/target/axion-boot-*.jar chat

# 启动 HTTP API 服务
java -jar axion-boot/target/axion-boot-*.jar serve --port 8080</code></pre>
          <div class="axion-cta-links">
            <a class="axion-btn-primary" :href="withBase(t('/zh/docs/what', '/docs/what'))">{{ t('查看文档', 'Read the Docs') }}</a>
            <a class="axion-btn-ghost" href="https://github.com/yALearner/Axion" target="_blank" rel="noopener">GitHub</a>
          </div>
        </div>
      </div>
    </section>

  </div>
</template>

<style scoped>
.axion-page {
  min-height: 100vh;
  background: #0b0f19;
  color: #e8edf6;
  font-family: inherit;
}

/* ── Hero ── */
.axion-hero {
  position: relative;
  padding: 100px 24px 80px;
  text-align: center;
  overflow: hidden;
  background:
    radial-gradient(ellipse 70% 55% at 50% -12%, rgba(45, 212, 191, 0.13), transparent 60%),
    radial-gradient(ellipse 55% 45% at 82% 8%, rgba(129, 140, 248, 0.12), transparent 60%),
    radial-gradient(ellipse 50% 40% at 15% 12%, rgba(34, 211, 238, 0.08), transparent 60%);
}
.axion-hero-inner {
  position: relative;
  max-width: 760px;
  margin: 0 auto;
  display: flex;
  flex-direction: column;
  align-items: center;
}
.axion-badge {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 6px 16px;
  border-radius: 20px;
  border: 1px solid #263248;
  background: #0e1420;
  color: #8fa0ba;
  font-size: 12px;
  margin-bottom: 28px;
}
.axion-badge-dot {
  width: 6px; height: 6px;
  border-radius: 50%;
  background: #2dd4bf;
  animation: pulse 2s infinite;
}
@keyframes pulse {
  0%,100% { opacity:1; transform:scale(1); }
  50% { opacity:0.4; transform:scale(1.4); }
}
.axion-title {
  margin: 0 0 12px;
  line-height: 1;
}
.axion-title-name {
  font-size: clamp(72px, 14vw, 120px);
  font-weight: 900;
  letter-spacing: -0.03em;
  color: #f4f7fb;
}
.axion-title-sub {
  font-size: 18px;
  color: #94a3b8;
  margin: 0 0 20px;
}
.axion-hero-desc {
  font-size: 16px;
  line-height: 1.7;
  color: #9fb0c9;
  max-width: 600px;
  margin: 0 0 32px;
}
.axion-hero-actions {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
  justify-content: center;
  margin-bottom: 20px;
}
.axion-btn-primary {
  padding: 11px 28px;
  border-radius: 8px;
  background: linear-gradient(135deg, #f4f7fb, #dbe6f5);
  color: #0b0f19;
  font-weight: 600;
  font-size: 14px;
  text-decoration: none;
  transition: opacity 0.2s, transform 0.15s, box-shadow 0.2s;
}
.axion-btn-primary:hover {
  opacity: 0.92;
  transform: translateY(-1px);
  box-shadow: 0 8px 28px rgba(34, 211, 238, 0.25);
}
.axion-btn-ghost {
  padding: 11px 28px;
  border-radius: 8px;
  border: 1px solid #263248;
  color: #c3cede;
  font-weight: 600;
  font-size: 14px;
  text-decoration: none;
  transition: border-color 0.2s, background 0.2s;
}
.axion-btn-ghost:hover { border-color: #2dd4bf; background: rgba(45, 212, 191, 0.06); }
.axion-hero-note {
  font-size: 12px;
  color: #6b7a93;
}

/* ── Section ── */
.axion-section { padding: 72px 24px; }
.axion-section-inner { max-width: 1000px; margin: 0 auto; }
.axion-primitives-inner { max-width: 1400px; }
.axion-section-header { text-align: center; margin-bottom: 48px; }
.axion-section-tag {
  display: inline-block;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.1em;
  text-transform: uppercase;
  color: #8fa0ba;
  padding: 4px 12px;
  border-radius: 20px;
  border: 1px solid #263248;
  background: #0e1420;
  margin-bottom: 14px;
}
.axion-section-title {
  font-size: clamp(22px, 4vw, 32px);
  font-weight: 700;
  color: #f4f7fb;
  margin: 0 0 12px;
}
.axion-section-desc {
  font-size: 15px;
  color: #94a3b8;
  max-width: 600px;
  margin: 0 auto;
  line-height: 1.6;
}

/* ── Problem ── */
.axion-problem {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 48px;
  align-items: start;
}
.axion-problem-text p { color: #94a3b8; line-height: 1.7; margin: 0 0 14px; font-size: 15px; }
.axion-problem-item { margin-bottom: 14px; }
.axion-problem-item strong { color: #f4f7fb; display: block; margin-bottom: 4px; font-size: 15px; }
.axion-problem-item { color: #94a3b8; line-height: 1.7; font-size: 14px; }
.axion-solution-line { color: #f4f7fb !important; font-weight: 600; margin-top: 20px !important; }
.axion-problem-compare { display: flex; flex-direction: column; gap: 16px; }
.axion-compare-item {
  padding: 20px;
  border-radius: 12px;
  border: 1px solid #1c2537;
}
.axion-compare-bad { background: #0e1420; }
.axion-compare-good { background: rgba(45,212,191,0.10); border-color: #2dd4bf; }
.axion-compare-label { font-size: 11px; font-weight: 700; color: #6b7a93; margin-bottom: 12px; text-transform: uppercase; letter-spacing: 0.08em; }
.axion-compare-rows { display: flex; flex-direction: column; gap: 8px; }
.axion-compare-row { display: flex; align-items: flex-start; gap: 10px; font-size: 13px; color: #8fa0ba; line-height: 1.5; }
.axion-compare-icon { flex-shrink: 0; font-style: normal; color: #64748b; font-weight: 700; width: 14px; }
.axion-icon-ok { color: #2dd4bf; }

/* ── Primitives ── */
.axion-primitives-section { background: #0e1420; }
.axion-primitives { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); grid-auto-rows: 1fr; gap: 16px; }
.axion-primitive {
  padding: 20px;
  border-radius: 14px;
  border: 1px solid #1c2537;
  background: #101623;
  display: flex;
  flex-direction: column;
  gap: 12px;
  transition: border-color 0.2s, box-shadow 0.2s;
  min-width: 0;
  overflow: hidden;
}
.axion-primitive .axion-code { flex: 1; }
.axion-primitive:hover { border-color: #2dd4bf; box-shadow: 0 6px 24px rgba(45, 212, 191, 0.15); }
.axion-primitive-header { display: flex; align-items: flex-start; gap: 12px; }
.axion-primitive-icon { font-size: 28px; flex-shrink: 0; }
.axion-primitive-title { font-size: 17px; font-weight: 700; color: #f4f7fb; margin: 0 0 2px; }
.axion-primitive-subtitle { font-size: 12px; color: #6b7a93; margin: 0; }
.axion-code {
  background: #0e1420;
  border: 1px solid #1c2537;
  border-radius: 8px;
  padding: 14px 16px;
  font-size: 12px;
  line-height: 1.6;
  color: #c3cede;
  overflow-x: auto;
  margin: 0;
  white-space: pre;
}
.axion-code code { font-family: 'JetBrains Mono', 'Fira Code', monospace; background: none; color: inherit; }

/* ── Scenarios ── */
.axion-scenarios { display: grid; grid-template-columns: repeat(2, 1fr); gap: 20px; }
.axion-scenario {
  display: flex;
  gap: 16px;
  padding: 20px;
  border-radius: 12px;
  border: 1px solid #1c2537;
  background: #0e1420;
}
.axion-scenario-num {
  font-size: 28px;
  font-weight: 900;
  color: #223049;
  line-height: 1;
  flex-shrink: 0;
  font-variant-numeric: tabular-nums;
}
.axion-scenario-title { font-size: 15px; font-weight: 600; color: #f4f7fb; margin: 0 0 6px; }
.axion-scenario-desc { font-size: 13px; color: #94a3b8; line-height: 1.6; margin: 0; }

/* ── Integration ── */
.axion-sdk-section { background: #0e1420; }
.axion-sdk-cards {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 20px;
}
.axion-sdk-card {
  background: #101623;
  border: 1px solid #1c2537;
  border-radius: 16px;
  padding: 28px 24px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.axion-sdk-card-icon { font-size: 28px; }
.axion-sdk-card-title { font-size: 17px; font-weight: 700; color: #f4f7fb; margin: 0; }
.axion-sdk-card-desc { font-size: 14px; color: #94a3b8; line-height: 1.6; margin: 0; flex: 1; }
.axion-sdk-installs { display: flex; flex-direction: column; gap: 6px; }
.axion-sdk-installs code {
  font-family: 'JetBrains Mono', 'Fira Code', monospace;
  font-size: 12px;
  background: #0e1420;
  border: 1px solid #1c2537;
  border-radius: 6px;
  padding: 5px 10px;
  color: #f4f7fb;
  display: block;
}

/* ── Module grid ── */
.axion-module-grid { display: flex; flex-direction: column; gap: 20px; }
.axion-module-group { display: flex; flex-direction: column; gap: 6px; }
.axion-module-group-label {
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.1em;
  text-transform: uppercase;
  color: #8fa0ba;
  margin-bottom: 4px;
}
.axion-module-row {
  display: flex;
  align-items: baseline;
  gap: 16px;
  padding: 8px 14px;
  border-radius: 8px;
  background: #0e1420;
  border: 1px solid #1c2537;
  flex-wrap: wrap;
}
.axion-module-name {
  font-family: 'JetBrains Mono', 'Fira Code', monospace;
  font-size: 12px;
  color: #f4f7fb;
  background: #16203a;
  border: 1px solid #263248;
  padding: 2px 8px;
  border-radius: 4px;
  flex-shrink: 0;
  white-space: nowrap;
}
.axion-module-desc { font-size: 13px; color: #94a3b8; flex: 1; }

/* ── CTA ── */
.axion-cta-section { background: #0e1420; }
.axion-cta { text-align: center; max-width: 680px; margin: 0 auto; }
.axion-cta-title { font-size: 28px; font-weight: 700; color: #f4f7fb; margin: 0 0 12px; }
.axion-cta-desc { font-size: 15px; color: #94a3b8; margin: 0 0 24px; }
.axion-cta-code { text-align: left; margin-bottom: 28px; }
.axion-cta-links { display: flex; gap: 12px; justify-content: center; flex-wrap: wrap; }

/* ── Flow diagram ── */
.axion-flow-section { padding: 0 24px 72px; }
.axion-flow-img {
  width: 100%;
  display: block;
  border: 1px solid #1c2537;
  border-radius: 12px;
}

/* ── Responsive ── */
@media (max-width: 900px) {
  .axion-sdk-cards { grid-template-columns: 1fr; }
}
@media (max-width: 768px) {
  .axion-hero { padding: 72px 20px 60px; }
  .axion-problem { grid-template-columns: 1fr; }
  .axion-primitives { grid-template-columns: 1fr; }
  .axion-scenarios { grid-template-columns: 1fr; }
  .axion-section { padding: 48px 20px; }
}
</style>
