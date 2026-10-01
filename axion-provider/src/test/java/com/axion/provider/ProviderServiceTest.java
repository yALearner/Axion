package com.oryxos.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.oryxos.core.Profile;
import com.oryxos.storage.LlmCall;
import com.oryxos.storage.LlmCallRepository;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

/** ProviderService 验收 harness——路由不串台、未知名报错、自动执行关闭、架构断言。 */
class ProviderServiceTest {

  private final LlmCallRepository audit = mock(LlmCallRepository.class);

  private static Profile profileUsing(String providerName) {
    return new Profile(
        "test-agent",
        null,
        new Profile.Identity(null, null),
        new Profile.ProviderRef(providerName, "test-model", 0.7),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        new Profile.Settings(null, null));
  }

  private static Prompt prompt() {
    return new Prompt(new UserMessage("hi"));
  }

  private static ChatResponse emptyResponse() {
    return new ChatResponse(List.of(new Generation(new AssistantMessage("ok"))));
  }

  @Test
  @DisplayName("按名路由：两个 provider 不串台")
  void routesByNameWithoutCrosstalk() {
    ChatModel deepseek = mock(ChatModel.class);
    ChatModel kimi = mock(ChatModel.class);
    when(kimi.call(any(Prompt.class))).thenReturn(emptyResponse());
    ProviderService service =
        new ProviderService(Map.of("deepseek", deepseek, "kimi", kimi), audit);

    service.chat("s-1", profileUsing("kimi"), prompt());

    verify(kimi, times(1)).call(any(Prompt.class));
    verify(deepseek, never()).call(any(Prompt.class));
  }

  @Test
  @DisplayName("未知名 provider：抛 ProviderNotFoundException")
  void unknownProviderThrows() {
    ProviderService service = new ProviderService(Map.of("deepseek", mock(ChatModel.class)), audit);

    assertThatThrownBy(() -> service.chat("s-1", profileUsing("kimi"), prompt()))
        .isInstanceOf(ProviderNotFoundException.class)
        .hasMessageContaining("kimi");
  }

  @Test
  @DisplayName("自动执行关闭：请求 options 断言 internalToolExecutionEnabled=false")
  void disablesInternalToolExecution() {
    ChatModel model = mock(ChatModel.class);
    when(model.call(any(Prompt.class))).thenReturn(emptyResponse());
    ProviderService service = new ProviderService(Map.of("deepseek", model), audit);

    service.chat("s-1", profileUsing("deepseek"), prompt());

    ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
    verify(model).call(captor.capture());
    ChatOptions options = captor.getValue().getOptions();
    assertThat(options).isInstanceOf(ToolCallingChatOptions.class);
    assertThat(ToolCallingChatOptions.isInternalToolExecutionEnabled(options)).isFalse();
  }

  @Test
  @DisplayName("调用失败：审计先落账（success=false + 原因）再上抛")
  void failureAuditsBeforeRethrow() {
    ChatModel model = mock(ChatModel.class);
    when(model.call(any(Prompt.class))).thenThrow(new RuntimeException("connect timeout"));
    ProviderService service = new ProviderService(Map.of("deepseek", model), audit);

    assertThatThrownBy(() -> service.chat("s-1", profileUsing("deepseek"), prompt()))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("connect timeout");

    ArgumentCaptor<LlmCall> captor = ArgumentCaptor.forClass(LlmCall.class);
    verify(audit).save(captor.capture());
    LlmCall recorded = captor.getValue();
    assertThat(recorded.getSuccess()).isFalse();
    assertThat(recorded.getErrorMessage()).contains("connect timeout");
    assertThat(recorded.getSessionId()).isEqualTo("s-1");
    assertThat(recorded.getProvider()).isEqualTo("deepseek");
  }

  @Test
  @DisplayName("调用成功：审计字段正确（provider/model/session/token）")
  void successAuditsFields() {
    Usage usage = mock(Usage.class);
    when(usage.getPromptTokens()).thenReturn(100);
    when(usage.getCompletionTokens()).thenReturn(50);
    when(usage.getTotalTokens()).thenReturn(150);
    ChatResponseMetadata metadata = mock(ChatResponseMetadata.class);
    when(metadata.getUsage()).thenReturn(usage);
    ChatModel model = mock(ChatModel.class);
    when(model.call(any(Prompt.class)))
        .thenReturn(
            new ChatResponse(List.of(new Generation(new AssistantMessage("ok"))), metadata));
    ProviderService service = new ProviderService(Map.of("deepseek", model), audit);

    service.chat("s-1", profileUsing("deepseek"), prompt());

    ArgumentCaptor<LlmCall> captor = ArgumentCaptor.forClass(LlmCall.class);
    verify(audit).save(captor.capture());
    LlmCall recorded = captor.getValue();
    assertThat(recorded.getSuccess()).isTrue();
    assertThat(recorded.getProvider()).isEqualTo("deepseek");
    assertThat(recorded.getModel()).isEqualTo("test-model");
    assertThat(recorded.getSessionId()).isEqualTo("s-1");
    assertThat(recorded.getPromptTokens()).isEqualTo(100);
    assertThat(recorded.getCompletionTokens()).isEqualTo(50);
    assertThat(recorded.getTotalTokens()).isEqualTo(150);
    assertThat(recorded.getDurationMs()).isNotNull();
    assertThat(recorded.getErrorMessage()).isNull();
  }

  @Test
  @DisplayName("架构断言：不靠扫描 ChatModel 集合区分 Provider")
  void noChatModelCollectionScanning() {
    boolean hasMapOfChatModel = false;
    for (Field field : ProviderService.class.getDeclaredFields()) {
      if (field.getType() == Map.class) {
        ParameterizedType type = (ParameterizedType) field.getGenericType();
        assertThat(type.getActualTypeArguments()[1])
            .withFailMessage("模型路由字段必须是 Map<String, ChatModel>")
            .isEqualTo(ChatModel.class);
        hasMapOfChatModel = true;
      }
      assertThat(field.getType())
          .withFailMessage("不得持有 ChatModel 集合用于扫描: " + field)
          .isNotEqualTo(List.class);
    }
    assertThat(hasMapOfChatModel).isTrue();
    for (Constructor<?> constructor : ProviderService.class.getDeclaredConstructors()) {
      assertThat(constructor.getParameterTypes())
          .withFailMessage("模型注入只能走显式 Map 构造")
          .contains(Map.class);
    }
  }
}
