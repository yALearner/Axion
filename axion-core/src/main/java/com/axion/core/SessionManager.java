package com.oryxos.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oryxos.storage.SessionEntity;
import com.oryxos.storage.SessionRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话管理器（JPA 版，003-cli 交付——002 跨节契约"第 18 节 JPA 化、契约不变、只换实现"）。
 *
 * <p>进程内 {@link ConcurrentHashMap} 缓存（活跃会话，保证同三元组两次 getOrCreate 返回同一实例的 002 契约断言）+ {@link
 * SessionRepository} 落库作持久化真相源（跨重启恢复）。同一 (channel, userId, profileName) 三元组幂等； 会话标识 = {@code
 * channel|userId|profileName}，**拼接只发生在本类一处**（H4 不变量四）。
 *
 * <p>{@link #save}：messages 整体序列化进 messages_json 落库 + 刷新 last_active_at（已存在行保留
 * created_at/status/archived_at）；Session ↔ SessionEntity 转换收口本类私有方法。
 */
public final class SessionManager {

  private final Map<String, Session> cache = new ConcurrentHashMap<>();
  private final SessionRepository sessionRepository;
  private final ObjectMapper objectMapper;

  public SessionManager(SessionRepository sessionRepository, ObjectMapper objectMapper) {
    this.sessionRepository = sessionRepository;
    this.objectMapper = objectMapper.copy();
  }

  /** 按三元组取或建会话（幂等：同三元组永远返回同一实例；库中命中则恢复历史）。 */
  public Session getOrCreate(String channel, String userId, String profileName) {
    String id = sessionIdOf(channel, userId, profileName);
    Session cached = cache.get(id);
    if (cached != null) {
      return cached;
    }
    Session restored = sessionRepository.findById(id).map(this::toSession).orElse(null);
    if (restored != null) {
      cache.put(id, restored);
      return restored;
    }
    Session created = new Session(id, profileName, channel, userId);
    cache.put(id, created);
    save(created);
    return created;
  }

  /** 按会话标识查：缓存优先，库中命中则反序列化重建（跨重启恢复路径）。 */
  public Optional<Session> get(String sessionId) {
    Session cached = cache.get(sessionId);
    if (cached != null) {
      return Optional.of(cached);
    }
    return sessionRepository
        .findById(sessionId)
        .map(
            entity -> {
              Session session = toSession(entity);
              cache.put(sessionId, session);
              return session;
            });
  }

  /** 持久化：messages 序列化落库 + last_active_at 刷新；已存在行保留 created_at/status/archived_at。 */
  public void save(Session session) {
    Instant now = Instant.now();
    String messagesJson = serialize(session.messages());
    SessionEntity entity =
        sessionRepository
            .findById(session.id())
            .map(
                existing -> {
                  existing.updateHistory(messagesJson, now);
                  return existing;
                })
            .orElseGet(
                () ->
                    new SessionEntity(
                        session.id(),
                        session.profileName(),
                        session.channel(),
                        session.userId(),
                        messagesJson,
                        "active",
                        now,
                        now,
                        null));
    sessionRepository.save(entity);
  }

  /** 归档会话（009-web-service DELETE /sessions/{id} 落点，拍板 A）：库里行状态流转 archived + 缓存移除。 */
  public void archive(String sessionId) {
    sessionRepository
        .findById(sessionId)
        .ifPresent(
            entity -> {
              entity.archive(Instant.now());
              sessionRepository.save(entity);
            });
    cache.remove(sessionId);
  }

  /** 会话标识拼接——全仓库只有这一个地方（H4 不变量四，SessionManagerTest 架构断言钉死）。 */
  private String sessionIdOf(String channel, String userId, String profileName) {
    return channel + "|" + userId + "|" + profileName;
  }

  /** SessionEntity → Session：反序列化 messages_json 重建历史。 */
  private Session toSession(SessionEntity entity) {
    Session session =
        new Session(
            entity.getSessionId(),
            entity.getProfileName(),
            entity.getChannel(),
            entity.getUserId());
    for (Message message : deserialize(entity.getMessagesJson())) {
      session.append(message);
    }
    return session;
  }

  private String serialize(List<Message> messages) {
    try {
      return objectMapper.writeValueAsString(messages);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("会话历史序列化失败", e);
    }
  }

  private List<Message> deserialize(String json) {
    if (json == null || json.isBlank()) {
      return List.of();
    }
    try {
      return objectMapper.readValue(json, new TypeReference<List<Message>>() {});
    } catch (Exception e) {
      throw new IllegalStateException("会话历史反序列化失败", e);
    }
  }
}
