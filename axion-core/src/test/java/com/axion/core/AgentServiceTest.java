package com.oryxos.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oryxos.storage.SessionRepository;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** AgentService 验收 harness——三触发源共用编排、ProfileContext 可取、坑四 finally 必清、结束后保存会话。 */
class AgentServiceTest {

  private final ProfileRegistry profileRegistry = new ProfileRegistry();
  private final ReActLoop reActLoop = mock(ReActLoop.class);
  private final SessionManager sessionManager = mock(SessionManager.class);

  private final Profile profile =
      new Profile(
          "ops-agent",
          null,
          new Profile.Identity(null, "你是一个助手"),
          new Profile.ProviderRef("deepseek", null, null),
          List.of(),
          List.of(),
          List.of(),
          List.of(),
          List.of(),
          new Profile.Settings(10, 20));

  private AgentService service() {
    return new AgentService(profileRegistry, reActLoop, sessionManager);
  }

  private Session session() {
    return newSessionManager().getOrCreate("cli", "alice", "ops-agent");
  }

  /** 003 起 SessionManager 为 JPA 版（构造注入仓储）；测试用 mock 仓储 + 真 ObjectMapper。 */
  private SessionManager newSessionManager() {
    SessionRepository repository = mock(SessionRepository.class);
    when(repository.findById(anyString())).thenReturn(java.util.Optional.empty());
    return new SessionManager(repository, new ObjectMapper());
  }

  @AfterEach
  void tearDown() {
    ProfileContext.clear(); // 测试间隔离：不让泄漏串到下一个用例
  }

  @Test
  @DisplayName("处理期间 ProfileContext 可取到当前 Agent 的 Profile（工具执行靠它知道当前是谁）")
  void profileContextAvailableDuringProcessing() {
    profileRegistry.register(profile);
    when(reActLoop.run(any(), anyString(), any()))
        .thenAnswer(
            inv -> {
              assertThat(ProfileContext.current()).isSameAs(profile);
              return "ok";
            });
    Session session = session();
    when(sessionManager.getOrCreate(anyString(), anyString(), anyString())).thenReturn(session);

    service().process(session, "hi");

    assertThat(ProfileContext.current()).isNull(); // 结束后已清理
  }

  @Test
  @DisplayName("坑四回归：处理抛异常时 finally 也把 ProfileContext 清掉")
  void profileContextClearedEvenWhenProcessingThrows() {
    profileRegistry.register(profile);
    when(reActLoop.run(any(), anyString(), any())).thenThrow(new RuntimeException("boom"));
    Session session = session();
    when(sessionManager.getOrCreate(anyString(), anyString(), anyString())).thenReturn(session);

    assertThatThrownBy(() -> service().process(session, "hi"))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("boom");

    assertThat(ProfileContext.current()).isNull();
  }

  @Test
  @DisplayName("正常结束：累积完历史的会话被保存（save 被调用）")
  void sessionSavedAfterProcessing() {
    profileRegistry.register(profile);
    when(reActLoop.run(any(), anyString(), any())).thenReturn("reply");
    Session session = session();
    when(sessionManager.getOrCreate(anyString(), anyString(), anyString())).thenReturn(session);

    service().process(session, "hi");

    verify(sessionManager).save(session);
  }

  @Test
  @DisplayName("Profile 从注册表按 session.profileName() 获取；未注册则清晰报错")
  void profileResolvedFromRegistryOrClearError() {
    Session session = session();
    when(sessionManager.getOrCreate(anyString(), anyString(), anyString())).thenReturn(session);

    assertThatThrownBy(() -> service().process(session, "hi"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("ops-agent");
  }

  @Test
  @DisplayName("⑨d：同一 Session 并发 process 串行化（三触发源同场的最小互斥）")
  void concurrentProcessOnSameSessionIsSerialized() throws Exception {
    profileRegistry.register(profile);
    java.util.concurrent.atomic.AtomicInteger active =
        new java.util.concurrent.atomic.AtomicInteger();
    java.util.concurrent.atomic.AtomicInteger maxActive =
        new java.util.concurrent.atomic.AtomicInteger();
    when(reActLoop.run(any(), anyString(), any()))
        .thenAnswer(
            inv -> {
              int now = active.incrementAndGet();
              maxActive.accumulateAndGet(now, Math::max);
              Thread.sleep(100);
              active.decrementAndGet();
              return "ok";
            });
    AgentService service = service(); // 共享同一实例——sessionLocks 是实例字段
    Session session = session();

    Thread t1 = Thread.ofVirtual().start(() -> service.process(session, "a"));
    Thread t2 = Thread.ofVirtual().start(() -> service.process(session, "b"));
    t1.join();
    t2.join();

    assertThat(maxActive.get()).isEqualTo(1); // 任何时刻至多一个 process 在跑——同会话串行
  }
}
