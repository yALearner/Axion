package com.axion.core;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Profile 内存索引：按 name 快速查找。011-plugin-agent 起支持运行时注册——启动扫描与运行时新增走同一段 {@code
 * AgentLoader.deriveProfile + register}（同一异常、同一消息，坑五）；{@code remove}/{@code exists} 是 30 节
 * 注销/更新的前置（001 FR-6 演进，FR-3）。
 */
public final class ProfileRegistry {

  private final Map<String, Profile> profiles = new ConcurrentHashMap<>();

  public void register(Profile profile) {
    profiles.put(profile.name(), profile);
  }

  /** 注销（幂等：不存在时静默）——30 节 DELETE 前置，本节交付运行时注册侧。 */
  public void remove(String name) {
    profiles.remove(name);
  }

  public boolean exists(String name) {
    return profiles.containsKey(name);
  }

  public Optional<Profile> findByName(String name) {
    return Optional.ofNullable(profiles.get(name));
  }

  public Collection<Profile> list() {
    return List.copyOf(profiles.values());
  }
}
