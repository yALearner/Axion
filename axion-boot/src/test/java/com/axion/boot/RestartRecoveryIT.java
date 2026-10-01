package com.oryxos.boot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oryxos.storage.LlmCallRepository;
import com.oryxos.storage.ScheduledTaskStore;
import com.oryxos.storage.ScheduledTaskView;
import com.oryxos.storage.SessionRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * RestartRecoveryIT（010-scheduler-mgmt，US5，@Tag("integration") 真 key 手动跑）——跑对话 + 触发定时后重启上下文：GET
 * /sessions/{id} 完整历史 / GET /memory 核心记忆 / GET /schedules 状态与历史（run_count/上次结果/下次触发）/ llm_calls 跨重启
 * 不断档——四样全恢复。无 key 时 assumeTrue 跳过（ProviderSmokeIT 同款，F2 口径）。
 */
@Tag("integration")
@SpringBootTest(classes = OryxOsApplication.class)
@AutoConfigureMockMvc
class RestartRecoveryIT {

  private static final String AGENT_DIR = ".oryxos/agents/weather-agent";
  private static final String DB_FILE = ".oryxos/restart-test.db"; // 类专属库

  static {
    // 与 E2E/MultiAgent 共用测试工作区：agents 目录整体重建、各自独占；真 key 对话用无 tools 的 weather-agent
    try {
      deleteRecursively(Path.of(".oryxos", "agents"));
      Files.createDirectories(Path.of(AGENT_DIR));
      Files.deleteIfExists(Path.of(DB_FILE));
      Files.writeString(
          Path.of(AGENT_DIR, "AGENT.md"),
          """
          ---
          name: weather-agent
          description: 重启恢复测试 Agent
          provider:
            name: deepseek
          schedules:
            - id: weather-8am
              cron: "0 0 8 * * *"
              zone: Asia/Shanghai
              message: 生成今日天气和穿搭建议
          ---
          你是天气助手，简短回答。
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
    // 有真 key 注入真 key；无 key 用 dummy 让 context 起得来、测试内 assumeTrue 跳过（F2 口径）
    registry.add(
        "oryxos.providers[0].api-key",
        () -> System.getenv().getOrDefault("DEEPSEEK_API_KEY", "dummy"));
    registry.add("oryxos.providers[0].base-url", () -> "https://api.deepseek.com");
    registry.add("oryxos.providers[1].name", () -> "kimi");
    registry.add("oryxos.providers[1].api-key", () -> "dummy");
    registry.add("oryxos.providers[1].base-url", () -> "http://127.0.0.1:9");
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private ScheduledTaskStore store;
  @Autowired private LlmCallRepository llmCallRepository;
  @Autowired private SessionRepository sessionRepository;

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  @DisplayName("重启四样恢复：sessions 历史 / memory / schedules 状态与历史 / llm_calls 跨重启不断档")
  void restartRecoversEverything() throws Exception {
    assumeTrue(
        System.getenv("DEEPSEEK_API_KEY") != null && !System.getenv("DEEPSEEK_API_KEY").isBlank(),
        "缺少 DEEPSEEK_API_KEY 环境变量，跳过重启恢复 IT");

    // 1) 真对话（真 key 走 ReAct）
    String createBody =
        objectMapper.writeValueAsString(new SessionCreateRequest("cli", "tester", "weather-agent"));
    String createResp =
        mockMvc
            .perform(
                post("/api/v1/sessions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(createBody))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String sessionId = objectMapper.readTree(createResp).get("data").get("sessionId").asText();

    String messageBody = objectMapper.writeValueAsString(new MessageRequest("北京今天天气怎么样"));
    mockMvc
        .perform(
            post("/api/v1/sessions/" + sessionId + "/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .content(messageBody))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0));

    // 2) 触发定时（runNow 真 key）
    mockMvc
        .perform(post("/api/v1/schedules/weather-8am/run"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.success").value(true));

    // 重启前快照
    long llmCallsBefore = llmCallRepository.count();
    assertThat(store.executions("weather-8am")).hasSize(1);
    assertThat(sessionRepository.findById(sessionId)).isPresent();

    // 3) 重启：同 db 文件起第二个上下文（web NONE——只验数据面，Tomcat 不重复占端口）
    try (ConfigurableApplicationContext restarted =
        new SpringApplicationBuilder(OryxOsApplication.class).web(WebApplicationType.NONE).run()) {
      ScheduledTaskStore restartedStore = restarted.getBean(ScheduledTaskStore.class);

      // ④ GET /schedules 状态与历史：run_count/上次结果/执行历史原样
      ScheduledTaskView task =
          restartedStore.list().stream()
              .filter(t -> t.taskId().equals("weather-8am"))
              .findFirst()
              .orElseThrow();
      assertThat(task.runCount()).isEqualTo(1); // 重注册保留状态列（重启不失忆）
      assertThat(task.lastStatus()).isEqualTo("success");
      assertThat(task.nextRunAt()).isNotNull();
      assertThat(restartedStore.executions("weather-8am")).hasSize(1); // 历史一条仍在

      // GET /sessions/{id} 完整历史（重启后同一行可查、messages 非空）
      assertThat(restarted.getBean(SessionRepository.class).findById(sessionId))
          .hasValueSatisfying(
              entity -> {
                assertThat(entity.getMessagesJson()).isNotBlank();
                try {
                  JsonNode messages = objectMapper.readTree(entity.getMessagesJson());
                  assertThat(messages.isArray()).isTrue();
                  assertThat(messages.size()).isGreaterThanOrEqualTo(2); // 用户消息 + 回复
                } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                  throw new AssertionError("messages_json 非法 JSON", e);
                }
              });

      // llm_calls 跨重启不断档（重启后计数只增不减）
      assertThat(restarted.getBean(LlmCallRepository.class).count())
          .isGreaterThanOrEqualTo(llmCallsBefore);
    }

    // GET /memory 核心记忆：markdown 后端文件在重启前后一致可读（本 IT 不写记忆——验证读取链路恢复）
    mockMvc
        .perform(get("/api/v1/memory"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0));
  }

  // 与 009 端点契约同形状的请求体（不引 web 模块的 DTO 记录——测试自持）
  private record SessionCreateRequest(String channel, String userId, String profileName) {}

  private record MessageRequest(String content) {}
}
