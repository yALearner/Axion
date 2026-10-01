package com.oryxos.provider;

import com.oryxos.storage.LlmCallRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Provider 装配（Spring 内部接线，非对外 API）： 读取全局层 {@code oryxos.providers}，逐条创建 {@link ChatModel}， 构建
 * provider name → ChatModel 的显式映射（禁止类型扫描），校验缺失即报错。
 */
@Configuration
public class ProviderConfiguration {

  @Bean
  public ProviderService providerService(
      Environment environment, LlmCallRepository llmCallRepository) {
    List<ProviderProperties> providers =
        Binder.get(environment)
            .bind("oryxos.providers", Bindable.listOf(ProviderProperties.class))
            .orElseThrow(
                () -> new IllegalStateException("缺少全局层配置 oryxos.providers（application.yaml）"));
    Map<String, ChatModel> providerMap = new LinkedHashMap<>();
    for (ProviderProperties provider : providers) {
      validate(provider);
      OpenAiApi api =
          OpenAiApi.builder().apiKey(provider.getApiKey()).baseUrl(provider.getBaseUrl()).build();
      OpenAiChatModel model =
          OpenAiChatModel.builder()
              .openAiApi(api)
              .defaultOptions(OpenAiChatOptions.builder().model(provider.getModel()).build())
              .build();
      providerMap.put(provider.getName(), model);
    }
    return new ProviderService(providerMap, llmCallRepository);
  }

  /** 配置缺失或非法 → 启动即报错，不静默失败。 */
  private void validate(ProviderProperties provider) {
    if (provider.getName() == null || provider.getName().isBlank()) {
      throw new IllegalStateException("oryxos.providers 列表项缺少 name");
    }
    if (provider.getApiKey() == null || provider.getApiKey().isBlank()) {
      throw new IllegalStateException(
          "provider [" + provider.getName() + "] 缺少 api-key（需 ${ENV_VAR} 环境变量占位）");
    }
  }
}
