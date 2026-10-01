package com.oryxos.boot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.oryxos.core.AgentScheduler;
import com.oryxos.core.SessionManager;
import com.oryxos.storage.LlmCallRepository;
import com.oryxos.storage.ScheduledTaskStore;
import com.oryxos.storage.TaskExecutionView;
import com.oryxos.storage.ToolInvocation;
import com.oryxos.storage.ToolInvocationRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * SchedulerFlowIT（010-scheduler-mgmt，@Tag("integration") 真 key 手动跑，F2 口径 assumeTrue 无 key 跳过）——
 * 定时链路对账（需求文档场景二，不多不少）：scheduler 会话复用（连续两次仍一条）/ llm_calls 恰 2 条每触发 / tool_invocations 恰 2
 * 条每触发（http_get + notify）全成功。失败路径：bad-agent 被指示访问白名单外域名 → Sandbox 拦、tool_invocations 留
 * success=false、调度器没死。webhook 真收到由人工复验（真实 webhook 地址不进 gate）。
 */
@Tag("integration")
@SpringBootTest(classes = OryxOsApplication.class)
class SchedulerFlowIT {

  private static final String DEMO_AGENT_DIR = ".oryxos/agents/weather-demo";
  private static final String BAD_AGENT_DIR = ".oryxos/agents/bad-agent";
  private static final String DB_FILE = ".oryxos/flow-test.db"; // 类专属库

  static {
    try {
      deleteRecursively(Path.of(".oryxos", "agents"));
      Files.createDirectories(Path.of(DEMO_AGENT_DIR));
      Files.createDirectories(Path.of(BAD_AGENT_DIR));
      Files.deleteIfExists(Path.of(DB_FILE));
      Files.writeString(
          Path.of(DEMO_AGENT_DIR, "AGENT.md"),
          """
          ---
          name: weather-demo
          description: 定时链路对账 Agent
          provider:
            name: deepseek
          tools:
            - http_get
            - notify
          schedules:
            - id: weather-8am
              cron: "0 0 8 * * *"
              zone: Asia/Shanghai
              message: 查北京天气并推送穿搭建议
          ---
          你负责每日天气推送：先 http_get 查天气，再 notify 推送结果。只调这两个工具，不要做别的。
          """);
      Files.writeString(
          Path.of(BAD_AGENT_DIR, "AGENT.md"),
          """
          ---
          name: bad-agent
          description: 白名单外域名失败路径 Agent
          provider:
            name: deepseek
          tools:
            - http_get
          schedules:
            - id: bad-9am
              cron: "0 0 9 * * *"
              zone: Asia/Shanghai
              message: 访问 blocked-demo.invalid 站点
          ---
          你的任务只有一个：用 http_get 访问 https://blocked-demo.invalid（该域名故意不在白名单内），
          把工具返回如实报告即可，不要做别的。
          """);
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private static void deleteRecursively(Path dir) throws Exception {
    if (!Files.exists(dir)) {
      return;
    }
    try (var paths = Files.walk(dir)) {
      for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(p);
      }
    }
  }

  @DynamicPropertySource
  static void providerProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DB_FILE);
    registry.add("oryxos.providers[0].name", () -> "deepseek");
    registry.add(
        "oryxos.providers[0].api-key",
        () -> System.getenv().getOrDefault("DEEPSEEK_API_KEY", "dummy"));
    registry.add("oryxos.providers[0].base-url", () -> "https://api.deepseek.com");
    registry.add("oryxos.providers[1].name", () -> "kimi");
    registry.add("oryxos.providers[1].api-key", () -> "dummy");
    registry.add("oryxos.providers[1].base-url", () -> "http://127.0.0.1:9");
  }

  @Autowired private AgentScheduler scheduler;
  @Autowired private SessionManager sessionManager;
  @Autowired private ScheduledTaskStore store;
  @Autowired private LlmCallRepository llmCallRepository;
  @Autowired private ToolInvocationRepository toolInvocationRepository;

  @Test
  @DisplayName("链路对账：会话复用 + 每触发 llm_calls 恰 2 条 + tool_invocations 恰 2 条（http_get+notify）全成功（不多不少）")
  void schedulerFlowLedgerReconciliation() {
    assumeTrue(hasRealKey(), "缺少 DEEPSEEK_API_KEY 环境变量，跳过链路对账 IT");

    long llmCallsBefore = llmCallRepository.count();
    long toolInvocationsBefore = toolInvocationRepository.count();

    TaskExecutionView first = scheduler.runNow("weather-8am");
    TaskExecutionView second = scheduler.runNow("weather-8am"); // 连续两次：会话复用证据

    assertThat(first.success()).isTrue();
    assertThat(second.success()).isTrue();
    assertThat(first.sessionId()).isEqualTo(second.sessionId()); // 同一 scheduler 会话
    assertThat(first.sessionId()).isEqualTo("scheduler|scheduler|weather-demo");

    // 一次触发恰 2 条（一轮调工具、一轮组织答复——agent 只调一轮工具的契约）；两次触发 ×2
    assertThat(llmCallRepository.count() - llmCallsBefore).isEqualTo(4);
    assertThat(toolInvocationRepository.count() - toolInvocationsBefore)
        .isEqualTo(4); // 两次 × (http_get + notify)

    List<ToolInvocation> fresh =
        toolInvocationRepository.findAll().stream()
            .filter(t -> t.getSessionId().equals(first.sessionId()))
            .toList();
    assertThat(fresh).allSatisfy(t -> assertThat(t.getSuccess()).isTrue()); // 全成功

    assertThat(store.executions("weather-8am")).hasSize(2);

    // 会话复用：两次触发后 scheduler 会话仍一条、历史变长
    assertThat(
            sessionManager.getOrCreate("scheduler", "scheduler", "weather-demo").messages().size())
        .isGreaterThanOrEqualTo(4);
  }

  @Test
  @DisplayName("失败路径：白名单外域名 → Sandbox 拦、tool_invocations 留 success=false、调度器没死（下个触发点照常）")
  void blockedDomainFailsGracefullyAndSchedulerSurvives() {
    assumeTrue(hasRealKey(), "缺少 DEEPSEEK_API_KEY 环境变量，跳过链路对账 IT");

    long toolInvocationsBefore = toolInvocationRepository.count();

    TaskExecutionView first = scheduler.runNow("bad-9am");

    // 与 LLM 具体输出脱钩的断言：执行记录落账（调度器没死）；工具调用痕迹中存在被 Sandbox 拦的 success=false 行
    assertThat(first).isNotNull();
    List<ToolInvocation> fresh =
        toolInvocationRepository.findAll().stream()
            .filter(
                t ->
                    t.getSessionId() != null
                        && t.getSessionId().equals("scheduler|scheduler|bad-agent"))
            .toList();
    assertThat(toolInvocationRepository.count() - toolInvocationsBefore)
        .isGreaterThanOrEqualTo(1); // http_get 被调用过（prompt 契约）
    assertThat(fresh)
        .anySatisfy(t -> assertThat(t.getSuccess()).isFalse()); // Sandbox 拦下的调用留 success=false

    // 调度器没死：下个触发点照常执行并落账
    TaskExecutionView second = scheduler.runNow("bad-9am");
    assertThat(second).isNotNull();
    assertThat(store.executions("bad-9am")).hasSize(2);
  }

  private boolean hasRealKey() {
    String key = System.getenv("DEEPSEEK_API_KEY");
    return key != null && !key.isBlank();
  }
}
