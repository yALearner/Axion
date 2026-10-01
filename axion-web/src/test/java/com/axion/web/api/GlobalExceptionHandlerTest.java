package com.oryxos.web.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * GlobalExceptionHandler 验收 harness（课件 §四）——standalone 装配真实 Handler，测试专用 Controller 抛各类异常。
 * 覆盖：每类异常映射约定状态码；信封边界（错误均 ErrorResponse：errorCode/message/timestamp、无 code 字段）；最值钱回归兼 ⑨a
 * 回归（IllegalStateException → 500 非 503 + 统一话术 + 内幕不泄漏）。
 */
class GlobalExceptionHandlerTest {

  /** 测试专用 Controller：按路径参数抛各类异常（非交付物，仅 harness 用）。 */
  @RestController
  static class ThrowingController {

    @GetMapping("/boom/{type}")
    void boom(@PathVariable String type) {
      switch (type) {
        case "invalid" -> throw new InvalidRequestException("bad input");
        case "session" -> throw new SessionNotFoundException("s-1");
        case "resource" -> throw new ResourceNotFoundException("no such agent");
        case "provider" -> throw new ProviderUnavailableException("provider down");
        case "timeout" -> throw new AgentTimeoutException("too slow");
        case "illegal" ->
            throw new IllegalStateException("jdbc:sqlite:/data/oryxos.db connect failed");
        default -> throw new RuntimeException("other");
      }
    }
  }

  private final MockMvc mockMvc =
      MockMvcBuilders.standaloneSetup(new ThrowingController())
          .setControllerAdvice(new GlobalExceptionHandler())
          .build();

  @Test
  @DisplayName("映射：400（InvalidRequest）/ 404（Session/Resource）/ 503（Provider）/ 504（Timeout）")
  void mapsExceptionTypesToStatusCodes() throws Exception {
    mockMvc.perform(get("/boom/invalid")).andExpect(status().isBadRequest());
    mockMvc.perform(get("/boom/session")).andExpect(status().isNotFound());
    mockMvc.perform(get("/boom/resource")).andExpect(status().isNotFound());
    mockMvc.perform(get("/boom/provider")).andExpect(status().isServiceUnavailable());
    mockMvc.perform(get("/boom/timeout")).andExpect(status().isGatewayTimeout());
  }

  @Test
  @DisplayName("信封边界（拍板 B 漂移防线）：错误响应均为 ErrorResponse——有 errorCode、无 code 字段")
  void errorResponsesUseErrorResponseEnvelope() throws Exception {
    mockMvc
        .perform(get("/boom/invalid"))
        .andExpect(jsonPath("$.errorCode").value(400))
        .andExpect(jsonPath("$.message").exists())
        .andExpect(jsonPath("$.timestamp").exists())
        .andExpect(jsonPath("$.code").doesNotExist()); // 错误信封不含成功信封的 code 字段
  }

  @Test
  @DisplayName("最值钱回归兼 ⑨a：IllegalStateException → 500（非 503）+ 统一话术 + 内幕不泄漏")
  void internalExceptionNeverLeaksDetails() throws Exception {
    mockMvc
        .perform(get("/boom/illegal"))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.errorCode").value(500))
        .andExpect(jsonPath("$.message").value("服务器内部错误")) // 统一话术（地基文案保真）
        .andExpect(
            content().string(Matchers.not(Matchers.containsString("jdbc:sqlite")))); // 内幕一个字不漏
  }

  @Test
  @DisplayName("兜底：未预期异常 → 500 统一话术")
  void unexpectedExceptionFallsBackTo500() throws Exception {
    mockMvc
        .perform(get("/boom/other"))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.errorCode").value(500))
        .andExpect(jsonPath("$.message").value("服务器内部错误"));
  }
}
