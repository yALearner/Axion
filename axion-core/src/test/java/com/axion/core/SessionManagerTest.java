package com.oryxos.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oryxos.storage.SessionEntity;
import com.oryxos.storage.SessionRepository;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * SessionManager 契约验收 harness（003 改造：JPA 版，mock SessionRepository）——幂等、三元组隔离、id 拼接只此 一处（H4
 * 不变量四）、save 触发落库、get 反序列化重建（跨重启恢复语义）。002 契约断言全部保留。
 */
class SessionManagerTest {

  private final SessionRepository repository = mock(SessionRepository.class);
  private final ObjectMapper mapper = new ObjectMapper();

  private SessionManager manager() {
    return new SessionManager(repository, mapper);
  }

  private static String messagesJson() {
    return "[{\"role\":\"USER\",\"content\":\"hi\",\"toolCalls\":[],\"toolResults\":[]}]";
  }

  private static SessionEntity entityOf(String id, String messagesJson) {
    return new SessionEntity(
        id,
        "ops-agent",
        "cli",
        "alice",
        messagesJson,
        "active",
        Instant.parse("2026-09-02T12:00:00Z"),
        Instant.parse("2026-09-02T12:05:00Z"),
        null);
  }

  @Test
  @DisplayName("同一三元组两次 getOrCreate 返回同一个 Session（幂等）")
  void getOrCreateSameTripleReturnsSameInstance() {
    when(repository.findById(any())).thenReturn(Optional.empty());
    SessionManager manager = manager(); // 同一实例（同一进程内缓存）

    Session first = manager.getOrCreate("cli", "alice", "ops-agent");
    Session second = manager.getOrCreate("cli", "alice", "ops-agent");

    assertThat(second).isSameAs(first); // 进程内缓存保证同一实例（002 契约）
    assertThat(second.id()).isEqualTo(first.id());
  }

  @Test
  @DisplayName("channel/user/profileName 任一不同则得到不同 Session")
  void differentTriplePartsYieldDifferentSessions() {
    when(repository.findById(any())).thenReturn(Optional.empty());
    SessionManager manager = manager();

    Session base = manager.getOrCreate("cli", "alice", "ops-agent");

    assertThat(manager.getOrCreate("web", "alice", "ops-agent")).isNotSameAs(base);
    assertThat(manager.getOrCreate("cli", "bob", "ops-agent")).isNotSameAs(base);
    assertThat(manager.getOrCreate("cli", "alice", "weather-agent")).isNotSameAs(base);
  }

  @Test
  @DisplayName("会话标识按 channel|user|profileName 公式生成")
  void sessionIdFollowsTripleFormula() {
    when(repository.findById(any())).thenReturn(Optional.empty());

    Session session = manager().getOrCreate("cli", "alice", "ops-agent");

    assertThat(session.id()).isEqualTo("cli|alice|ops-agent");
  }

  @Test
  @DisplayName("get 按 id 命中：从库里反序列化重建历史（跨重启恢复语义）")
  void getRestoresHistoryFromRepository() {
    when(repository.findById("cli|alice|ops-agent"))
        .thenReturn(Optional.of(entityOf("cli|alice|ops-agent", messagesJson())));

    Optional<Session> restored = manager().get("cli|alice|ops-agent");

    assertThat(restored).isPresent();
    assertThat(restored.get().messages()).hasSize(1);
    assertThat(restored.get().messages().get(0).role()).isEqualTo(Message.MessageRole.USER);
    assertThat(restored.get().messages().get(0).content()).isEqualTo("hi");
    assertThat(restored.get().profileName()).isEqualTo("ops-agent");
  }

  @Test
  @DisplayName("⑨b：发消息后 get 返回缓存实例的最新历史（Web 读侧缓存/库一致性回归钉）")
  void getReturnsLatestMessagesAfterAppend() {
    when(repository.findById(any())).thenReturn(Optional.empty());
    SessionManager manager = manager();
    Session created = manager.getOrCreate("cli", "alice", "ops-agent");
    created.append(Message.user("latest"));

    Optional<Session> fetched = manager.get("cli|alice|ops-agent");

    assertThat(fetched).isPresent();
    assertThat(fetched.get()).isSameAs(created); // 缓存优先——同一实例
    assertThat(fetched.get().messages()).hasSize(1); // 最新消息可见
  }

  @Test
  @DisplayName("getOrCreate 未命中缓存但库里命中 → 反序列化重建而不是新建（重启后同三元组恢复历史）")
  void getOrCreateRestoresWhenRepositoryHit() {
    when(repository.findById("cli|alice|ops-agent"))
        .thenReturn(Optional.of(entityOf("cli|alice|ops-agent", messagesJson())));

    Session session = manager().getOrCreate("cli", "alice", "ops-agent");

    assertThat(session.messages()).hasSize(1); // 历史恢复
    verify(repository, times(0)).save(any()); // 恢复路径不重复落库
  }

  @Test
  @DisplayName("新建路径：save 触发落库，messages 序列化 + last_active_at 更新")
  void savePersistsSerializedHistory() {
    when(repository.findById(any())).thenReturn(Optional.empty());
    SessionManager manager = manager();

    Session session = manager.getOrCreate("cli", "alice", "ops-agent");
    session.append(Message.user("hi"));

    manager.save(session);

    // create 路径已在 getOrCreate 内落过一次账（新建即持久化），显式 save 是第二次——断言最后一次落库内容
    ArgumentCaptor<SessionEntity> captor = ArgumentCaptor.forClass(SessionEntity.class);
    verify(repository, times(2)).save(captor.capture());
    SessionEntity saved = captor.getValue();
    assertThat(saved.getSessionId()).isEqualTo("cli|alice|ops-agent");
    assertThat(saved.getMessagesJson()).contains("\"content\":\"hi\"");
    assertThat(saved.getStatus()).isEqualTo("active");
    assertThat(saved.getLastActiveAt()).isNotNull();
  }

  @Test
  @DisplayName("save 已存在行：保留 created_at/status，只刷新 messages 与 last_active_at")
  void saveUpdatesExistingRowPreservingTimestamps() {
    SessionEntity existing = entityOf("cli|alice|ops-agent", messagesJson());
    Instant originalCreatedAt = existing.getCreatedAt();
    when(repository.findById("cli|alice|ops-agent")).thenReturn(Optional.of(existing));

    SessionManager manager = manager();
    Session session = manager.getOrCreate("cli", "alice", "ops-agent"); // 恢复路径
    session.append(Message.assistant("新回复"));

    manager.save(session);

    verify(repository).save(existing);
    assertThat(existing.getCreatedAt()).isEqualTo(originalCreatedAt); // created_at 保留
    assertThat(existing.getMessagesJson()).contains("新回复");
  }

  @Test
  @DisplayName("架构断言：Session 无公开构造器——id 只能由 SessionManager 生成（H4 不变量四）")
  void sessionCannotBeConstructedOutsideSessionManager() {
    boolean hasPublicConstructor =
        Arrays.stream(Session.class.getDeclaredConstructors())
            .anyMatch(c -> Modifier.isPublic(c.getModifiers()));

    assertThat(hasPublicConstructor)
        .withFailMessage("Session 不得有公开构造器：会话标识的拼接只能发生在 SessionManager 一处")
        .isFalse();
  }

  @Test
  @DisplayName("会话历史经 append 累积，外部只能读不可变视图")
  void messagesAccumulateAndExposeUnmodifiableView() {
    when(repository.findById(any())).thenReturn(Optional.empty());
    Session session = manager().getOrCreate("cli", "alice", "ops-agent");
    session.append(Message.user("hi"));
    session.append(Message.assistant("hello"));

    assertThat(session.messages()).hasSize(2);
    assertThat(session.messages().get(0).content()).isEqualTo("hi");
    assertThat(session.messages()).isUnmodifiable();
  }
}
