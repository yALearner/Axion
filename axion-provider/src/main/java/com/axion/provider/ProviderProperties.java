package com.oryxos.provider;

/**
 * 全局层 Provider 声明（application.yaml 的 {@code oryxos.providers} 列表项）。
 *
 * <p>由 {@code ProviderConfiguration} 通过 Spring Boot Binder 绑定为列表； 每个实例解决"连不连得上"：接的是谁、凭证从哪个环境变量来。
 */
public class ProviderProperties {

  /** Provider 唯一名（如 deepseek）；Profile 引用必须命中。 */
  private String name;

  /** 凭证来源：只允许 ${ENV_VAR} 占位，禁止明文。 */
  private String apiKey;

  /** 可选接入地址（OpenAI 兼容端点）。 */
  private String baseUrl;

  /** 默认模型名（Profile 可覆盖）。 */
  private String model;

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getApiKey() {
    return apiKey;
  }

  public void setApiKey(String apiKey) {
    this.apiKey = apiKey;
  }

  public String getBaseUrl() {
    return baseUrl;
  }

  public void setBaseUrl(String baseUrl) {
    this.baseUrl = baseUrl;
  }

  public String getModel() {
    return model;
  }

  public void setModel(String model) {
    this.model = model;
  }
}
