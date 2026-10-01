package com.oryxos.provider;

/** Profile 引用的 provider 名不在显式映射表中——报错必须清晰，不得悄悄用错。 */
public class ProviderNotFoundException extends RuntimeException {

  public ProviderNotFoundException(String providerName) {
    super("Provider 不存在: " + providerName);
  }
}
