package com.oryxos.web.api;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oryxos.core.AgentService;
import com.oryxos.core.Session;
import com.oryxos.core.SessionManager;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * SessionApiController 验收 harness（课件 §四）——standalone MockMvc 只起 MVC 层、mock
 * AgentService/SessionManager，不碰模型不碰库 （oryxos-web 无主类，standalone 模式直接装配 Controller + 真实
 * GlobalExceptionHandler，语义与 @WebMvcTest 切片等价）。 覆盖：超 32KB → 400；Session 不存在 → 404；正常请求
 * agentService.process 恰被调一次（薄 Controller 机器证据）。
 */
class SessionApiControllerTest {

  private final AgentService agentService = mock(AgentService.class);
  private final SessionManager sessionManager = mock(SessionManager.class);

  private final MockMvc mockMvc =
      MockMvcBuilders.standaloneSetup(new SessionApiController(agentService, sessionManager))
          .setControllerAdvice(new GlobalExceptionHandler())
          .build();

  private final ObjectMapper mapper = new ObjectMapper();

  private Session session() {
    return mock(Session.class);
  }

  @Test
  @DisplayName("发消息：正常请求 agentService.process 恰被调一次（薄 Controller 机器证据）")
  void sendProcessesExactlyOnce() throws Exception {
    Session session = session();
    when(session.id()).thenReturn("s-1");
    when(sessionManager.get("s-1")).thenReturn(Optional.of(session));
    when(agentService.process(eq(session), anyString())).thenReturn("你好");

    mockMvc
        .perform(
            post("/api/v1/sessions/s-1/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"hi\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.reply").value("你好"));

    verify(agentService).process(eq(session), eq("hi")); // 恰一次——Controller 没夹带私货
  }

  @Test
  @DisplayName("防呆：超 32KB → 400")
  void sendRejectsOversizedMessage() throws Exception {
    String big = "x".repeat(32 * 1024 + 1);

    mockMvc
        .perform(
            post("/api/v1/sessions/s-1/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(java.util.Map.of("content", big))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value(400));
  }

  @Test
  @DisplayName("防呆：空消息 → 400")
  void sendRejectsBlankMessage() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/sessions/s-1/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"   \"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value(400));
  }

  @Test
  @DisplayName("Session 不存在 → 404")
  void sendRejectsUnknownSession() throws Exception {
    when(sessionManager.get("no-such")).thenReturn(Optional.empty());

    mockMvc
        .perform(
            post("/api/v1/sessions/no-such/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"hi\"}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.errorCode").value(404));
  }

  @Test
  @DisplayName("查历史：返回最近 100 条以内的消息")
  void historyReturnsMessages() throws Exception {
    Session session = session();
    when(session.id()).thenReturn("s-1");
    when(sessionManager.get("s-1")).thenReturn(Optional.of(session));
    when(session.messages()).thenReturn(java.util.List.of());

    mockMvc
        .perform(get("/api/v1/sessions/s-1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0));
  }
}
