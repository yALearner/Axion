package com.oryxos.web.api;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.oryxos.core.AgentScheduler;
import com.oryxos.storage.ScheduledTaskStore;
import com.oryxos.storage.ScheduledTaskView;
import com.oryxos.storage.TaskExecutionView;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * ScheduleApiController 验收 harness（standalone 同款装配，009 模式）：四端点契约（双信封）+ 404 映射 + PUT 缺 enabled 400 +
 * run 返回 TaskExecutionView（与历史条目同形状）。504 映射由 GlobalExceptionHandlerTest 覆盖（60s 真等待不可行， 009 同口径）。
 */
class ScheduleApiControllerTest {

  private static final Instant BASE = Instant.parse("2026-09-09T00:00:00Z");

  private final AgentScheduler scheduler = mock(AgentScheduler.class);
  private final ScheduledTaskStore store = mock(ScheduledTaskStore.class);

  // standalone 默认 mapper 无 JavaTimeModule（Instant → epoch 数字）——显式配成与 Boot 默认一致（ISO-8601 字符串）
  private final ObjectMapper objectMapper =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

  private final MockMvc mockMvc =
      MockMvcBuilders.standaloneSetup(new ScheduleApiController(scheduler, store))
          .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
          .setControllerAdvice(new GlobalExceptionHandler())
          .build();

  private ScheduledTaskView task(boolean enabled) {
    return new ScheduledTaskView(
        "weather-8am",
        "weather-agent",
        "0 0 8 * * *",
        "Asia/Shanghai",
        "生成今日天气和穿搭建议",
        enabled,
        BASE,
        BASE.minusSeconds(60),
        "success",
        3);
  }

  private TaskExecutionView execution() {
    return new TaskExecutionView(
        42L, "weather-8am", "scheduler|scheduler|weather-agent", BASE, true, null, 8213L);
  }

  private void registerTask() {
    when(store.list()).thenReturn(List.of(task(true)));
  }

  @Test
  @DisplayName("GET /schedules：双信封列表（taskId/profile/cron/nextRunAt/lastStatus/runCount/enabled 齐备）")
  void listReturnsEnvelope() throws Exception {
    registerTask();

    mockMvc
        .perform(get("/api/v1/schedules"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.length()").value(1))
        .andExpect(jsonPath("$.data[0].taskId").value("weather-8am"))
        .andExpect(jsonPath("$.data[0].profileName").value("weather-agent"))
        .andExpect(jsonPath("$.data[0].cron").value("0 0 8 * * *"))
        .andExpect(jsonPath("$.data[0].zone").value("Asia/Shanghai"))
        .andExpect(jsonPath("$.data[0].nextRunAt").value("2026-09-09T00:00:00Z"))
        .andExpect(jsonPath("$.data[0].lastStatus").value("success"))
        .andExpect(jsonPath("$.data[0].runCount").value(3))
        .andExpect(jsonPath("$.data[0].enabled").value(true));
  }

  @Test
  @DisplayName("GET /schedules/{id}/executions：历史列表信封（最新在前由 store 保证）")
  void executionsReturnsEnvelope() throws Exception {
    registerTask();
    when(store.executions("weather-8am")).thenReturn(List.of(execution()));

    mockMvc
        .perform(get("/api/v1/schedules/weather-8am/executions"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.length()").value(1))
        .andExpect(jsonPath("$.data[0].id").value(42))
        .andExpect(jsonPath("$.data[0].taskId").value("weather-8am"))
        .andExpect(jsonPath("$.data[0].sessionId").value("scheduler|scheduler|weather-agent"))
        .andExpect(jsonPath("$.data[0].success").value(true))
        .andExpect(jsonPath("$.data[0].durationMs").value(8213));
  }

  @Test
  @DisplayName(
      "POST /schedules/{id}/run：同步等待返回 TaskExecutionView（与历史条目同形状，Clarifications 2026-09-09）")
  void runReturnsExecutionView() throws Exception {
    registerTask();
    when(scheduler.runNow("weather-8am")).thenReturn(execution());

    mockMvc
        .perform(post("/api/v1/schedules/weather-8am/run"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.id").value(42))
        .andExpect(jsonPath("$.data.taskId").value("weather-8am"))
        .andExpect(jsonPath("$.data.success").value(true))
        .andExpect(jsonPath("$.data.errorMessage").doesNotExist())
        .andExpect(jsonPath("$.data.durationMs").value(8213));

    verify(scheduler).runNow("weather-8am"); // 恰一次
  }

  @Test
  @DisplayName("PUT /schedules/{id}：enabled 切换生效（setEnabled 仅改 enabled，next_run_at 不动）")
  void updateTogglesEnabled() throws Exception {
    registerTask();
    when(store.list()).thenReturn(List.of(task(true)), List.of(task(false))); // 切换前后

    mockMvc
        .perform(
            put("/api/v1/schedules/weather-8am")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":false}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.enabled").value(false));

    verify(store).setEnabled("weather-8am", false); // 只动 enabled
  }

  @Test
  @DisplayName("PUT 缺 enabled 字段：400 + 错误信封（InvalidRequestException 单出口）")
  void updateWithoutEnabledReturns400() throws Exception {
    registerTask();

    mockMvc
        .perform(
            put("/api/v1/schedules/weather-8am")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value(400))
        .andExpect(jsonPath("$.message").value("请求参数非法：enabled 必填"));

    verify(store, never()).setEnabled(anyString(), org.mockito.ArgumentMatchers.anyBoolean());
  }

  @Test
  @DisplayName("任务不存在：四端点按 id 访问 → 404 + 错误信封（run 不触发、setEnabled 不执行）")
  void unknownTaskReturns404() throws Exception {
    when(store.list()).thenReturn(List.of());

    mockMvc
        .perform(get("/api/v1/schedules/ghost/executions"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.errorCode").value(404))
        .andExpect(jsonPath("$.message").value("定时任务不存在: ghost"));

    mockMvc
        .perform(post("/api/v1/schedules/ghost/run"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.errorCode").value(404));

    mockMvc
        .perform(
            put("/api/v1/schedules/ghost")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":false}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.errorCode").value(404));

    verify(scheduler, never()).runNow(anyString());
    verify(store, never()).setEnabled(anyString(), org.mockito.ArgumentMatchers.anyBoolean());
  }
}
