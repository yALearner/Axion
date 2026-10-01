package com.oryxos.cli;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;
import picocli.CommandLine.Command;

/**
 * provider list 命令（轻命令）——读 classpath `application.yaml` 解析 `oryxos.providers` 打印
 * name/model/base-url；**api-key 永不输出**（凭证零泄漏不变量）。
 */
@Command(name = "list", description = "列出已配置的 Provider", mixinStandardHelpOptions = true)
public class ProviderListCommand implements Runnable {

  @Override
  @SuppressWarnings("unchecked")
  public void run() {
    InputStream in = ProviderListCommand.class.getResourceAsStream("/application.yaml");
    if (in == null) {
      System.out.println("未找到 application.yaml（Provider 配置在 oryxos-boot）");
      return;
    }
    try (InputStream stream = in) {
      Map<String, Object> config = new Yaml().load(stream);
      Object providers =
          config.getOrDefault("oryxos", Map.of()) instanceof Map<?, ?> oryxos
              ? oryxos.get("providers")
              : null;
      if (!(providers instanceof List<?> list) || list.isEmpty()) {
        System.out.println("（无 Provider 配置）");
        return;
      }
      for (Object item : list) {
        if (item instanceof Map<?, ?> p) {
          System.out.printf(
              "%s | model=%s | base-url=%s%n", p.get("name"), p.get("model"), p.get("base-url"));
        }
      }
    } catch (java.io.IOException e) {
      throw new IllegalStateException("Provider 配置读取失败: " + e.getMessage(), e);
    }
  }
}
