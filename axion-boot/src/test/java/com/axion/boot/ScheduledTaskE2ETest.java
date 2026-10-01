package com.oryxos.boot;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.oryxos.core.AgentScheduler;
import com.oryxos.core.Profile;
import com.oryxos.core.ProfileRegistry;
import com.oryxos.provider.ProviderService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * ScheduledTaskE2ETest（010-scheduler-mgmt FR 验收，课件原文五步）——@SpringBootTest 起真实上下文（mock provider、gate
 * 内无 key）：① 临时工作区放带 schedules（含 id）的 Agent（cron 远期，靠 runNow 手动触发不等时间）② 启动即登记 → GET /schedules
 * 有任务、run_count=0、enabled=true ③ POST run 立即执行 → 走真实 ReAct（mock 两段式：先 save_memory 工具调用、再最终答复） ④
 * 断言落库：run_count=1、last_status=success、executions 一条成功、GET /memory 查得到写入 ⑤ PUT 停用 → 列表显示已停用 +
 * 停用后到点不触发、不记历史。
 */
@SpringBootTest(classes = OryxOsApplication.class)
@AutoConfigureMockMvc
class ScheduledTaskE2ETest {

  private static final String AGENT_DIR = ".oryxos/agents/weather-agent";
  private static final String DB_FILE =
      ".oryxos/e2e-test.db"; // 类专属库：与 MultiAgentIsolationTest 各自独立（Windows 打开中的文件不可删）

  static {
    // 坑八：surefire 工作目录 = 模块目录，相对路径数据源/工作区落 oryxos-boot/.oryxos——先清库、再放 mock Agent（008 实录）。
    // agents 目录整体重建：与 MultiAgentIsolationTest 共用同一测试工作区，各自独占所需 Agent（测试类加载顺序不定）。
    try {
      deleteRecursively(Path.of(".oryxos", "agents"));
      Files.createDirectories(Path.of(AGENT_DIR));
      Files.createDirectories(Path.of(".oryxos", "memory"));
      Files.deleteIfExists(Path.of(DB_FILE)); // 全新库：登记断言 run_count=0 不受前次测试污染
      Files.writeString(Path.of(".oryxos", "memory", "MEMORY.md"), ""); // GET /memory 断言干净起点
      Files.writeString(
          Path.of(AGENT_DIR, "AGENT.md"),
          """
          ---
          name: weather-agent
          description: E2E 定时任务测试 Agent
          provider:
            name: deepseek
          tools:
            - save_memory
          schedules:
            - id: weather-8am
              cron: "0 0 8 * * *"
              zone: Asia/Shanghai
              message: 生成今日天气和穿搭建议
          ---
          E2E 测试专用 Agent 正文
          """);
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  /** 递归删除（测试工作区专属——生产 .oryxos 在仓库根，与此无关）。 */
  private static void deleteRecursively(Path dir) throws Exception {
    if (!Files.exists(dir)) {
      return;
    }
    try (var paths = Files.walk(dir)) {
      for (Path p : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(p);
      }
    }
  }

  @DynamicPropertySource
  static void providerProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DB_FILE); // 类专属库（F2 独立工作区口径）
    // 坑表：索引式覆盖必须补全整元素字段（008 实录）
    registry.add("oryxos.providers[0].name", () -> "deepseek");
    registry.add("oryxos.providers[0].api-key", () -> "dummy");
    registry.add("oryxos.providers[0].base-url", () -> "http://127.0.0.1:9");
    registry.add("oryxos.providers[1].name", () -> "kimi");
    registry.add("oryxos.providers[1].api-key", () -> "dummy");
    registry.add("oryxos.providers[1].base-url", () -> "http://127.0.0.1:9");
  }

  /** T002 核实：Spring Framework 6.2 的 @MockitoBean 替换真实 Provider bean（注入点全量收 mock）。 */
  @MockitoBean private ProviderService providerService;

  @Autowired private MockMvc mockMvc;
  @Autowired private AgentScheduler scheduler;
  @Autowired private ProfileRegistry profileRegistry;

  @BeforeEach
  void stubProviderTwoPhase() {
    // 两段式 mock：第一轮返回 save_memory 工具调用（走真实 ToolExecutor + MemoryService），第二轮返回最终答复。
    // H3 核实：spring-ai 1.1.8 带工具调用的四参构造为 protected——测试用匿名子类走通（类非 final）。
    AssistantMessage toolCall =
        new AssistantMessage(
            "",
            Map.of(),
            List.of(
                new AssistantMessage.ToolCall(
                    "call-1", "function", "save_memory", "{\"content\":\"用户喜欢极简风格\"}")),
            List.of()) {};
    AssistantMessage finalAnswer = new AssistantMessage("今日天气晴朗，适合出行");
    when(providerService.chat(anyString(), any(), any()))
        .thenReturn(
            new ChatResponse(List.of(new Generation(toolCall))),
            new ChatResponse(List.of(new Generation(finalAnswer))));
  }

  @Test
  @DisplayName("E2E 五步：登记 → 列表 → 立即执行 → 落库断言（含 GET /memory）→ 停用后到点不触发不记历史")
  void scheduledTaskLifecycleInFiveSteps() throws Exception {
    // ② 启动即登记：GET /schedules 有任务、run_count=0、enabled=true、next_run_at 非空
    mockMvc
        .perform(get("/api/v1/schedules"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.length()").value(1))
        .andExpect(jsonPath("$.data[0].taskId").value("weather-8am"))
        .andExpect(jsonPath("$.data[0].profileName").value("weather-agent"))
        .andExpect(jsonPath("$.data[0].runCount").value(0))
        .andExpect(jsonPath("$.data[0].enabled").value(true))
        .andExpect(jsonPath("$.data[0].nextRunAt").isNotEmpty());

    // ③ POST run 立即执行：走真实 ReAct（mock provider 两段式），同步返回执行记录
    mockMvc
        .perform(post("/api/v1/schedules/weather-8am/run"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.taskId").value("weather-8am"))
        .andExpect(jsonPath("$.data.success").value(true));

    // ④ 断言落库：run_count=1、last_status=success、executions 一条成功、GET /memory 查得到写入
    mockMvc
        .perform(get("/api/v1/schedules"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].runCount").value(1))
        .andExpect(jsonPath("$.data[0].lastStatus").value("success"))
        .andExpect(jsonPath("$.data[0].lastRunAt").isNotEmpty());

    mockMvc
        .perform(get("/api/v1/schedules/weather-8am/executions"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(1))
        .andExpect(jsonPath("$.data[0].success").value(true))
        .andExpect(jsonPath("$.data[0].sessionId").value("scheduler|scheduler|weather-agent"));

    mockMvc
        .perform(get("/api/v1/memory"))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.data.content").value(org.hamcrest.Matchers.containsString("用户喜欢极简风格")));

    // ⑤ PUT 停用 → 列表显示已停用；停用后到点不触发、不记历史（最值钱回归）
    mockMvc
        .perform(
            put("/api/v1/schedules/weather-8am")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":false}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.enabled").value(false));

    mockMvc.perform(get("/api/v1/schedules")).andExpect(jsonPath("$.data[0].enabled").value(false));

    Profile weatherAgent = profileRegistry.findByName("weather-agent").orElseThrow();
    scheduler.runOnce(weatherAgent, weatherAgent.schedules().get(0)); // 到点触发（直调，不等 cron）
    mockMvc
        .perform(get("/api/v1/schedules/weather-8am/executions"))
        .andExpect(jsonPath("$.data.length()").value(1)); // 仍是 1 条——停用后不执行不记历史
  }
}
