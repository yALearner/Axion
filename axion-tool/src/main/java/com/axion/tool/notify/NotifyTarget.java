package com.oryxos.tool.notify;

import java.util.Map;

/**
 * 推送目标（FR-2）：channelType + config——具体是 webhook 地址还是别的认证信息由实现类自己解释，接口层 不携带任何实现细节。
 *
 * <p>config 键约定（E1 复盘修复，2026-09-05 拍板）：{@link #KEY_URL} = 推送地址、{@link #KEY_NAME} = 渠道注册名
 * （供审计结果带渠道名）；键名不再以字面量散落调用方，统一经本类常量与便捷访问器 {@link #url()} / {@link #name()} 取用 （缺失或为空时明确报错，不静默
 * null——S4 空串防线）。
 *
 * @param channelType 渠道类型（adapter 显式 Map 的选键；核心阶段为 "webhook"）
 * @param config 渠道配置（核心阶段键约定见 {@link #KEY_URL} / {@link #KEY_NAME}）
 */
public record NotifyTarget(String channelType, Map<String, String> config) {

  /** config 键约定：推送地址。 */
  public static final String KEY_URL = "url";

  /** config 键约定：渠道注册名（审计结果带渠道名用）。 */
  public static final String KEY_NAME = "name";

  /** 防御性拷贝：不暴露可变映射的内部表示（JsonSchema 同款先例）。 */
  public NotifyTarget {
    config = Map.copyOf(config);
  }

  @Override
  public Map<String, String> config() {
    return Map.copyOf(config);
  }

  /** 推送地址；缺失或为空时明确报错，不静默 null。 */
  public String url() {
    return require(KEY_URL);
  }

  /** 渠道注册名；缺失或为空时明确报错，不静默 null。 */
  public String name() {
    return require(KEY_NAME);
  }

  private String require(String key) {
    String value = config.get(key);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("NotifyTarget.config 键 " + key + " 缺失或为空");
    }
    return value;
  }
}
