package com.oryxos.web.api;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.oryxos.core.AgentService;
import com.oryxos.core.Profile;
import com.oryxos.core.ProfileRegistry;
import com.oryxos.core.Session;
import com.oryxos.core.SessionManager;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * AgentApiController 验收 harness（standalone 同款装配）：invoke 走 process 恰一次、三元组 ("web","invoke",name) 断言、
 * Agent 不存在 → 404、成功返回 ApiResponse 信封。
 */
class AgentApiControllerTest {

  private final AgentService agentService = mock(AgentService.class);
  private final SessionManager sessionManager = mock(SessionManager.class);
  private final ProfileRegistry profileRegistry = new ProfileRegistry();

  private final MockMvc mockMvc =
      MockMvcBuilders.standaloneSetup(
              new AgentApiController(agentService, sessionManager, profileRegistry))
          .setControllerAdvice(new GlobalExceptionHandler())
          .build();

  private void registerAgent(String name) {
    profileRegistry.register(
        new Profile(
            name,
            null,
            new Profile.Identity(null, "你是一个助手"),
            new Profile.ProviderRef("deepseek", null, null),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            new Profile.Settings(10, 20)));
  }

  @Test
  @DisplayName("invoke：一次性 Session 三元组固定 (web, invoke, agentName) + process 恰被调一次")
  void invokeUsesOneShotSessionTriple() throws Exception {
    registerAgent("weather-agent");
    Session oneShot = mock(Session.class);
    when(sessionManager.getOrCreate(eq("web"), eq("invoke"), eq("weather-agent")))
        .thenReturn(oneShot);
    when(agentService.process(eq(oneShot), anyString())).thenReturn("今天晴");

    mockMvc
        .perform(
            post("/api/v1/agents/weather-agent/invoke")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"今天天气怎么样\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.reply").value("今天晴"));

    verify(sessionManager).getOrCreate("web", "invoke", "weather-agent"); // ⑨ 三元组钉死
    verify(agentService).process(eq(oneShot), eq("今天天气怎么样")); // 恰一次
  }

  @Test
  @DisplayName("Agent 不存在 → 404")
  void invokeRejectsUnknownAgent() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/agents/no-such/invoke")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"hi\"}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.errorCode").value(404));
  }
}
