package com.oryxos.boot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.oryxos.core.AgentScheduler;
import com.oryxos.core.Message;
import com.oryxos.core.Profile;
import com.oryxos.core.ProfileRegistry;
import com.oryxos.core.PromptBuilder;
import com.oryxos.core.Session;
import com.oryxos.core.SessionManager;
import com.oryxos.provider.ProviderService;
import com.oryxos.storage.ScheduledTaskStore;
import com.oryxos.storage.TaskExecutionView;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * MultiAgentIsolationTest（010-scheduler-mgmt，US5）——两差异 Profile（A 只文件工具、B 只 HTTP 工具）同实例三边界： 工具隔离（A 的
 * prompt 上下文不含 B 独有工具）/ 会话隔离（各自会话互不串）/ 定时隔离（A 定时异常，B 下个触发点照常）。 mock provider 按 profile
 * 分派：file-agent 抛异常、http-agent 正常答复——gate 内无 key 可跑。
 */
@SpringBootTest(classes = OryxOsApplication.class)
class MultiAgentIsolationTest {

  private static final String AGENT_A_DIR = ".oryxos/agents/file-agent";
  private static final String AGENT_B_DIR = ".oryxos/agents/http-agent";
  private static final String DB_FILE = ".oryxos/multiagent-test.db"; // 类专属库：与 E2E 各自独立

  static {
    // 与 ScheduledTaskE2ETest 共用测试工作区：agents 目录整体重建、各自独占（测试类加载顺序不定，见 E2E 同款注释）
    try {
      deleteRecursively(Path.of(".oryxos", "agents"));
      Files.createDirectories(Path.of(AGENT_A_DIR));
      Files.createDirectories(Path.of(AGENT_B_DIR));
      Files.deleteIfExists(Path.of(DB_FILE));
      Files.writeString(
          Path.of(AGENT_A_DIR, "AGENT.md"),
          """
          ---
          name: file-agent
          description: 只文件工具
          provider:
            name: deepseek
          tools:
            - read_file
          schedules:
            - id: file-9am
              cron: "0 0 9 * * *"
              zone: Asia/Shanghai
              message: 检查日志
          ---
          A 正文
          """);
      Files.writeString(
          Path.of(AGENT_B_DIR, "AGENT.md"),
          """
          ---
          name: http-agent
          description: 只 HTTP 工具
          provider:
            name: deepseek
          tools:
            - http_get
          schedules:
            - id: http-10am
              cron: "0 0 10 * * *"
              zone: Asia/Shanghai
              message: 抓取新闻
          ---
          B 正文
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
    registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DB_FILE); // 类专属库（F2 独立工作区口径）
    registry.add("oryxos.providers[0].name", () -> "deepseek");
    registry.add("oryxos.providers[0].api-key", () -> "dummy");
    registry.add("oryxos.providers[0].base-url", () -> "http://127.0.0.1:9");
    registry.add("oryxos.providers[1].name", () -> "kimi");
    registry.add("oryxos.providers[1].api-key", () -> "dummy");
    registry.add("oryxos.providers[1].base-url", () -> "http://127.0.0.1:9");
  }

  @MockitoBean private ProviderService providerService;

  @Autowired private ProfileRegistry profileRegistry;
  @Autowired private PromptBuilder promptBuilder;
  @Autowired private SessionManager sessionManager;
  @Autowired private AgentScheduler scheduler;
  @Autowired private ScheduledTaskStore store;

  private Profile profileA;
  private Profile profileB;

  @BeforeEach
  void setUp() {
    profileA = profileRegistry.findByName("file-agent").orElseThrow();
    profileB = profileRegistry.findByName("http-agent").orElseThrow();
    // mock provider 按 profile 分派：file-agent 定时抛异常（定时隔离用）；其余正常答复
    when(providerService.chat(anyString(), any(), any()))
        .thenAnswer(
            inv -> {
              Profile profile = inv.getArgument(1);
              if ("file-agent".equals(profile.name())) {
                throw new RuntimeException("模型调用失败");
              }
              return new ChatResponse(List.of(new Generation(new AssistantMessage("今日新闻如下"))));
            });
  }

  @Test
  @DisplayName(
      "工具隔离：A 的 prompt options 含 read_file 不含 http_get；B 反之（PromptBuilder.selectTools 按 Profile"
          + " 过滤）")
  void toolIsolationInPromptContext() {
    Session sessionA = sessionManager.getOrCreate("cli", "alice", "file-agent");
    sessionA.append(Message.user("hi"));

    // 工具列表在 Prompt options（Function Calling 格式）而非文本——按 ToolDefinition.name 断言
    List<String> toolNamesA = toolNamesOf(promptBuilder.build(sessionA, profileA));
    assertThat(toolNamesA).contains("read_file").doesNotContain("http_get");

    Session sessionB = sessionManager.getOrCreate("cli", "alice", "http-agent");
    sessionB.append(Message.user("hi"));

    List<String> toolNamesB = toolNamesOf(promptBuilder.build(sessionB, profileB));
    assertThat(toolNamesB).contains("http_get").doesNotContain("read_file");
  }

  private List<String> toolNamesOf(org.springframework.ai.chat.prompt.Prompt prompt) {
    // H3 核实：getToolCallbacks 在 ToolCallingChatOptions 上（ChatOptions 接口不暴露）——按实际类型收窄
    org.springframework.ai.chat.prompt.ChatOptions options = prompt.getOptions();
    if (options instanceof org.springframework.ai.model.tool.ToolCallingChatOptions toolOptions) {
      return toolOptions.getToolCallbacks().stream()
          .map(cb -> cb.getToolDefinition().name())
          .toList();
    }
    return List.of();
  }

  @Test
  @DisplayName("会话隔离：两 Agent 各自会话独立、历史互不串")
  void sessionIsolation() {
    // 测试专属 user（SessionManager 内存缓存跨方法存活，同三元组会复用上一条用例的会话）
    Session sessionA = sessionManager.getOrCreate("cli", "alice-iso", "file-agent");
    Session sessionB = sessionManager.getOrCreate("cli", "alice-iso", "http-agent");

    assertThat(sessionA.id()).isNotEqualTo(sessionB.id());
    sessionA.append(Message.user("A 的消息"));
    assertThat(sessionB.messages()).isEmpty(); // B 会话不串 A 的历史
  }

  @Test
  @DisplayName("定时隔离：A 定时抛异常后 B 下个触发点照常执行（单任务失败不拖调度器，NFR-2）")
  void schedulerIsolationAcrossProfiles() {
    scheduler.runOnce(profileA, profileA.schedules().get(0)); // A 定时异常
    scheduler.runOnce(profileB, profileB.schedules().get(0)); // B 照常

    List<TaskExecutionView> executionsA = store.executions("file-9am");
    List<TaskExecutionView> executionsB = store.executions("http-10am");
    assertThat(executionsA).hasSize(1);
    assertThat(executionsA.get(0).success()).isFalse(); // A 失败留痕
    assertThat(executionsA.get(0).errorMessage()).isEqualTo("模型调用失败"); // 人可读非堆栈
    assertThat(executionsB).hasSize(1);
    assertThat(executionsB.get(0).success()).isTrue(); // B 不受影响
  }
}
