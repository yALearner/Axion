package com.axion.web.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.axion.core.AgentLifecycleService;
import com.axion.core.AgentService;
import com.axion.core.MemoryService;
import com.axion.core.Profile;
import com.axion.core.ProfileRegistry;
import com.axion.core.Session;
import com.axion.core.SessionManager;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * AgentApiController 验收 harness（standalone 装配，012 扩展）：invoke 三元组 + 全生命周期端点薄转发 + 400/404/503 code
 * 各自区分（2.5 口径）+ 统一 ApiResponse 信封。
 */
class AgentApiControllerTest {

  private final AgentService agentService = mock(AgentService.class);
  private final SessionManager sessionManager = mock(SessionManager.class);
  private final ProfileRegistry profileRegistry = new ProfileRegistry();
  private final AgentLifecycleService lifecycle = mock(AgentLifecycleService.class);
  private final MemoryService memoryService = mock(MemoryService.class);

  private final MockMvc mockMvc =
      MockMvcBuilders.standaloneSetup(
              new AgentApiController(
                  agentService, sessionManager, profileRegistry, lifecycle, memoryService))
          .setControllerAdvice(new GlobalExceptionHandler())
          .build();

  private void registerAgent(String name) {
    profileRegistry.register(
        new Profile(
            name,
            "运维助手",
            new Profile.Identity(name, "你是一个助手"),
            new Profile.ProviderRef("deepseek", "deepseek-chat", null),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            new Profile.Settings(10, 20)));
  }

  // ── 009 存量回归（invoke） ──

  @Test
  @DisplayName("invoke：一次性 Session 三元组固定 (web, invoke, agentName) + process 恰被调一次（009 回归）")
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
  @DisplayName("invoke：Agent 不存在 → 404（009 回归）")
  void invokeRejectsUnknownAgent() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/agents/no-such/invoke")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"hi\"}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.errorCode").value(404));
  }

  // ── US1：创建（坑十八：重名第一步就拒，一个目录都不写） ──

  @Test
  @DisplayName("坑十八：create 重名 → 400 AGENT_EXISTS（lifecycle 不被调，一个目录都不写）")
  void createRejectsDuplicateNameBeforeLifecycle() throws Exception {
    registerAgent("ops-agent");

    mockMvc
        .perform(
            post("/api/v1/agents")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"ops-agent\",\"description\":\"运维助手\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value(400))
        .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("已存在")));

    verify(lifecycle, never()).create(anyString(), anyString()); // 一个目录都不写
  }

  @Test
  @DisplayName("create 薄转发：返回 AgentView 信封")
  void createDelegatesAndWrapsEnvelope() throws Exception {
    Profile created = profile("ops-agent");
    when(lifecycle.create("ops-agent", "运维助手")).thenReturn(created);

    mockMvc
        .perform(
            post("/api/v1/agents")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"ops-agent\",\"description\":\"运维助手\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.name").value("ops-agent"));

    verify(lifecycle).create("ops-agent", "运维助手");
  }

  @Test
  @DisplayName("list：注册表全部 Agent → AgentView 列表")
  void listReturnsAllAgents() throws Exception {
    registerAgent("a");
    registerAgent("b");

    mockMvc
        .perform(get("/api/v1/agents"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(2));
  }

  // ── US3：更新/删除/404 ──

  @Test
  @DisplayName("update：整份 AGENT.md 覆写薄转发；不存在 → 404 AGENT_NOT_FOUND")
  void updateDelegatesAndRejectsUnknown() throws Exception {
    registerAgent("ops-agent");
    when(lifecycle.update(eq("ops-agent"), anyString())).thenReturn(profile("ops-agent"));

    mockMvc
        .perform(
            put("/api/v1/agents/ops-agent")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"agentMd\":\"---\\nname: ops-agent\\n---\\n新正文\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.name").value("ops-agent"));

    mockMvc
        .perform(
            put("/api/v1/agents/ghost")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"agentMd\":\"x\"}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.errorCode").value(404));
  }

  @Test
  @DisplayName("delete：薄转发（顺序由 lifecycle 单测钉死）；不存在 → 404")
  void deleteDelegatesAndRejectsUnknown() throws Exception {
    registerAgent("ops-agent");

    mockMvc
        .perform(delete("/api/v1/agents/ops-agent"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0));
    verify(lifecycle).delete("ops-agent");

    mockMvc
        .perform(delete("/api/v1/agents/ghost"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.errorCode").value(404));
  }

  // ── US4：生成/保存 ──

  @Test
  @DisplayName("generate-files：草稿返回不落盘；审计 sessionId 经 SessionManager（admin:console:<name>）")
  void generateFilesReturnsDraftWithAuditSession() throws Exception {
    registerAgent("ops-agent");
    Session consoleSession = mock(Session.class);
    when(consoleSession.id()).thenReturn("admin:console:ops-agent");
    when(sessionManager.getOrCreate("admin", "console", "ops-agent")).thenReturn(consoleSession);
    when(lifecycle.generateFiles("admin:console:ops-agent", "ops-agent", "查天气"))
        .thenReturn(Map.of("AGENT.md", "---\nname: ops-agent\n---\n正文"));

    mockMvc
        .perform(
            post("/api/v1/agents/ops-agent/generate-files")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"description\":\"查天气\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.files['AGENT.md']").exists());
  }

  @Test
  @DisplayName("坑十五：model 未配置 → 503 AUTHOR_MODEL_MISSING（可读原因）")
  void generateFilesMapsModelMissingTo503() throws Exception {
    registerAgent("ops-agent");
    Session consoleSession = mock(Session.class);
    when(consoleSession.id()).thenReturn("s-1");
    when(sessionManager.getOrCreate("admin", "console", "ops-agent")).thenReturn(consoleSession);
    when(lifecycle.generateFiles("s-1", "ops-agent", "查天气"))
        .thenThrow(new IllegalArgumentException(AgentLifecycleService.MODEL_MISSING_MESSAGE));

    mockMvc
        .perform(
            post("/api/v1/agents/ops-agent/generate-files")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"description\":\"查天气\"}"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.errorCode").value(503))
        .andExpect(
            jsonPath("$.message")
                .value(org.hamcrest.Matchers.containsString("axion.author.model")));
  }

  @Test
  @DisplayName("坑十四：生成产物非法 → 400 GENERATE_INVALID 可读原因")
  void generateFilesMapsInvalidOutputTo400() throws Exception {
    registerAgent("ops-agent");
    Session consoleSession = mock(Session.class);
    when(consoleSession.id()).thenReturn("s-1");
    when(sessionManager.getOrCreate("admin", "console", "ops-agent")).thenReturn(consoleSession);
    when(lifecycle.generateFiles("s-1", "ops-agent", "查天气"))
        .thenThrow(new IllegalArgumentException("生成产物不是合法 AGENT.md: 缺少 frontmatter"));

    mockMvc
        .perform(
            post("/api/v1/agents/ops-agent/generate-files")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"description\":\"查天气\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value(400))
        .andExpect(
            jsonPath("$.message").value(org.hamcrest.Matchers.containsString("不是合法 AGENT.md")));
  }

  @Test
  @DisplayName("save-files：薄转发；AGENT.md 非法 → 400 AGENT_INVALID（不写坏目录）")
  void saveFilesDelegatesAndRejectsInvalid() throws Exception {
    registerAgent("ops-agent");
    when(lifecycle.saveFiles(eq("ops-agent"), any())).thenReturn(profile("ops-agent"));

    mockMvc
        .perform(
            post("/api/v1/agents/ops-agent/files")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"files\":{\"AGENT.md\":\"---\\nname: ops-agent\\n---\\n正文\"}}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.name").value("ops-agent"));

    when(lifecycle.saveFiles(eq("ops-agent"), any()))
        .thenThrow(new IllegalArgumentException("AGENT.md 不是合法定义"));
    mockMvc
        .perform(
            post("/api/v1/agents/ops-agent/files")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"files\":{\"AGENT.md\":\"坏内容\"}}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value(400));
  }

  // ── US5：per-agent 记忆 + 固定会话 ──

  @Test
  @DisplayName("memory：readAll(name) 薄转发（per-agent 只读）")
  void memoryReadsPerAgent() throws Exception {
    registerAgent("ops-agent");
    when(memoryService.readAll("ops-agent")).thenReturn("## 核心记忆\n\n## 归档记忆\n");

    mockMvc
        .perform(get("/api/v1/agents/ops-agent/memory"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content").exists());

    verify(memoryService).readAll("ops-agent");
  }

  @Test
  @DisplayName(
      "session：固定三元组 (admin, console, name) getOrCreate 幂等（session_id 拼接只在 SessionManager）")
  void sessionUsesFixedAdminConsoleTriple() throws Exception {
    registerAgent("ops-agent");
    Session consoleSession = mock(Session.class);
    when(consoleSession.id()).thenReturn("admin:console:ops-agent");
    when(consoleSession.profileName()).thenReturn("ops-agent");
    when(consoleSession.messages()).thenReturn(List.of());
    when(sessionManager.getOrCreate("admin", "console", "ops-agent")).thenReturn(consoleSession);

    mockMvc
        .perform(get("/api/v1/agents/ops-agent/session"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.sessionId").value("admin:console:ops-agent"));

    verify(sessionManager).getOrCreate("admin", "console", "ops-agent"); // 恒为同一条
  }

  @Test
  @DisplayName("session/messages：往固定会话发消息触发 ReAct（宪法 VIII 同一引擎）")
  void sendMessageTriggersReActOnFixedSession() throws Exception {
    registerAgent("ops-agent");
    Session consoleSession = mock(Session.class);
    when(sessionManager.getOrCreate("admin", "console", "ops-agent")).thenReturn(consoleSession);
    when(agentService.process(eq(consoleSession), anyString())).thenReturn("回复");

    mockMvc
        .perform(
            post("/api/v1/agents/ops-agent/session/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"你好\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.reply").value("回复"));

    verify(agentService).process(eq(consoleSession), eq("你好"));
  }

  // ── US6：删除端到端语义（顺序由 lifecycle 单测钉死，此处验 404/信封） ──

  @Test
  @DisplayName("delete 端到端：归档后 GET 404（可追溯语义由 AgentStore/lifecycle 单测覆盖）")
  void deleteThenGetYields404() throws Exception {
    registerAgent("ops-agent");

    mockMvc.perform(delete("/api/v1/agents/ops-agent")).andExpect(status().isOk());
    profileRegistry.remove("ops-agent"); // 模拟 lifecycle 已移出索引（本测试只验控制器语义）

    mockMvc
        .perform(get("/api/v1/agents/ops-agent"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.errorCode").value(404));
  }

  private Profile profile(String name) {
    return new Profile(
        name,
        "运维助手",
        new Profile.Identity(name, "你是一个助手"),
        new Profile.ProviderRef("deepseek", "deepseek-chat", null),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        new Profile.Settings(10, 20));
  }
}
