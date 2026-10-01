package com.oryxos.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.oryxos.core.Profile;
import com.oryxos.storage.LlmCall;
import com.oryxos.storage.LlmCallRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;

/**
 * 集成冒烟（@Tag("integration")，CI 默认跳过）： 读环境变量真 key、真调一次、断言拿到非空响应且 llm_calls 落一条 success=true。
 *
 * <p>跑法：{@code mvn -pl oryxos-provider -am test -Dtest.groups=integration -Dtest.excludedGroups=}
 * （bash 前缀 {@code DEEPSEEK_API_KEY=xxx}；PowerShell 先 {@code $env:DEEPSEEK_API_KEY = "xxx"}）
 */
@Tag("integration")
class ProviderSmokeIT {

  @Test
  @DisplayName("真实 key 冒烟：调一次 deepseek，拿到非空响应且审计落 success=true")
  void smokeCallAgainstRealApi() {
    String apiKey = System.getenv("DEEPSEEK_API_KEY");
    Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(), "缺少 DEEPSEEK_API_KEY 环境变量，跳过冒烟");

    OpenAiApi api = OpenAiApi.builder().apiKey(apiKey).baseUrl("https://api.deepseek.com").build();
    ChatModel model =
        OpenAiChatModel.builder()
            .openAiApi(api)
            .defaultOptions(OpenAiChatOptions.builder().model("deepseek-chat").build())
            .build();
    LlmCallRepository repository = mock(LlmCallRepository.class);
    ProviderService service = new ProviderService(Map.of("deepseek", model), repository);
    Profile profile =
        new Profile(
            "smoke-agent",
            null,
            new Profile.Identity(null, null),
            new Profile.ProviderRef("deepseek", "deepseek-chat", 0.7),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            new Profile.Settings(null, null));

    ChatResponse response =
        service.chat("smoke-1", profile, new Prompt(new UserMessage("你好，只回复两个字：OK")));

    assertThat(response).isNotNull();
    assertThat(response.getResult().getOutput().getText()).isNotBlank();
    ArgumentCaptor<LlmCall> captor = ArgumentCaptor.forClass(LlmCall.class);
    verify(repository).save(captor.capture());
    LlmCall recorded = captor.getValue();
    assertThat(recorded.getSuccess()).isTrue();
    assertThat(recorded.getProvider()).isEqualTo("deepseek");
    assertThat(recorded.getSessionId()).isEqualTo("smoke-1");
  }
}
