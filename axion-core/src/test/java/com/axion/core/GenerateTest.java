package com.axion.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

/** 按描述重生成 harness（012 FR-7，5.2.4）——草稿可解析、不落盘不注册、非法 400 语义、围栏剥离、503 语义、LlmGateway 审计锚点。 */
class GenerateTest {

  private final AgentLoader agentLoader = new AgentLoader();
  private final ProfileRegistry profileRegistry = new ProfileRegistry();
  private final AgentScheduler agentScheduler = mock(AgentScheduler.class);
  private final AgentStore agentStore = mock(AgentStore.class);
  private final LlmGateway llmGateway = mock(LlmGateway.class);

  private AgentLifecycleService lifecycle;

  @BeforeEach
  void setUp() {
    lifecycle =
        new AgentLifecycleService(
            agentLoader,
            profileRegistry,
            agentScheduler,
            agentStore,
            llmGateway,
            p -> {},
            Set.of("deepseek"),
            "deepseek",
            "deepseek-chat");
  }

  private void stubLlm(String text) {
    Generation generation = mock(Generation.class);
    when(generation.getOutput())
        .thenReturn(new org.springframework.ai.chat.messages.AssistantMessage(text));
    when(llmGateway.chat(anyString(), any(), any()))
        .thenReturn(new ChatResponse(List.of(generation)));
  }

  @Test
  @DisplayName("草稿可被 AgentLoader 解析 + 只生成不落盘不注册（磁盘零变化）")
  void draftIsParsableAndTouchesNothing() {
    stubLlm("---\nname: ops-agent\nprovider:\n  name: deepseek\n---\n正文");

    Map<String, String> files = lifecycle.generateFiles("s-1", "ops-agent", "查天气");

    assertThat(files).containsKey("AGENT.md");
    assertThat(files.get("AGENT.md")).contains("name: ops-agent").contains("正文");
    verify(agentStore, never()).writeAll(anyString(), any()); // 不落盘
    verify(agentScheduler, never()).registerProfile(any()); // 不注册
    verify(llmGateway)
        .chat(anyString(), any(), any()); // LlmGateway 被调用（llm_calls 审计由 001 契约保证——宪法 V 锚点）
  }

  @Test
  @DisplayName("坑十四：LLM 产出非法 → IllegalArgumentException 可读原因（web 层映射 400），不落盘")
  void invalidOutputFailsWithReadableReason() {
    stubLlm("这不是 AGENT.md");

    assertThatThrownBy(() -> lifecycle.generateFiles("s-1", "ops-agent", "查天气"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不是合法 AGENT.md");
    verify(agentStore, never()).writeAll(anyString(), any());
  }

  @Test
  @DisplayName("坑十四：模型多吐的 ``` 围栏被剥掉")
  void codeFencesAreStripped() {
    stubLlm("```markdown\n---\nname: ops-agent\nprovider:\n  name: deepseek\n---\n正文\n```");

    Map<String, String> files = lifecycle.generateFiles("s-1", "ops-agent", "查天气");

    assertThat(files.get("AGENT.md")).doesNotContain("```").contains("name: ops-agent");
  }

  @Test
  @DisplayName("坑十五：model 未配置 → 503 语义（IllegalArgumentException 前缀），不向模型发 model=null")
  void missingModelFailsFastWithoutCallingGateway() {
    AgentLifecycleService noModel =
        new AgentLifecycleService(
            agentLoader,
            profileRegistry,
            agentScheduler,
            agentStore,
            llmGateway,
            p -> {},
            Set.of("deepseek"),
            "deepseek",
            null);

    assertThatThrownBy(() -> noModel.generateFiles("s-1", "ops-agent", "查天气"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageStartingWith(AgentLifecycleService.MODEL_MISSING_MESSAGE);
    verify(llmGateway, never()).chat(anyString(), any(), any());
  }

  @Test
  @DisplayName("5.2.4：已存在 Agent 沿用其 provider（保持可跑）")
  void generationReusesExistingAgentProvider() {
    profileRegistry.register(
        new Profile(
            "ops-agent",
            null,
            new Profile.Identity(null, null),
            new Profile.ProviderRef("kimi", "moonshot-v1", null),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            new Profile.Settings(10, 20)));
    when(llmGateway.chat(anyString(), any(), any()))
        .thenAnswer(
            inv -> {
              Profile authorProfile = inv.getArgument(1);
              assertThat(authorProfile.provider().name())
                  .isEqualTo("kimi"); // 沿用已存在 Agent 的 provider
              Generation generation = mock(Generation.class);
              when(generation.getOutput())
                  .thenReturn(
                      new org.springframework.ai.chat.messages.AssistantMessage(
                          "---\nname: ops-agent\nprovider:\n  name: kimi\n---\n正文"));
              return new ChatResponse(List.of(generation));
            });

    Map<String, String> files = lifecycle.generateFiles("s-1", "ops-agent", "查天气");

    assertThat(files.get("AGENT.md")).contains("name: ops-agent");
  }
}
