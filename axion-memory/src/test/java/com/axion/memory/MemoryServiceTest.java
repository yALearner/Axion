package com.oryxos.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oryxos.core.LongTermMemoryStore;
import com.oryxos.core.MemoryScope;
import com.oryxos.core.Message;
import com.oryxos.core.Session;
import com.oryxos.core.SessionManager;
import com.oryxos.storage.SessionRepository;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * MemoryService 统一门面 harness（US1）——buildContext 组合核心记忆 + 会话历史、归档不整体注入（只经 load 截断后
 * 进入）；remember/recall 正确委托 LongTermMemoryStore（接口墙）。
 */
class MemoryServiceTest {

  /** 003 起 SessionManager 为 JPA 版（构造注入仓储）；测试用 mock 仓储 + 真 ObjectMapper。 */
  private Session newSession() {
    SessionRepository repository = mock(SessionRepository.class);
    when(repository.findById(org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(java.util.Optional.empty());
    Session session =
        new SessionManager(repository, new ObjectMapper()).getOrCreate("cli", "alice", "ops-agent");
    session.append(Message.user("hi"));
    session.append(Message.assistant("你好"));
    return session;
  }

  @Test
  @DisplayName("buildContext = 核心记忆 + 会话历史；归档不整体注入（仅经 load 截断后进入）")
  void buildContextCombinesCoreMemoryAndSessionHistory() {
    LongTermMemoryStore store = mock(LongTermMemoryStore.class);
    when(store.load())
        .thenReturn("## 核心记忆\n- [2026-09-06] 项目用 Spring Boot\n## 归档记忆\n- [2026-09-06] 归档条目A");
    MemoryServiceImpl service = new MemoryServiceImpl(store);

    String context = service.buildContext(newSession());

    assertThat(context)
        .contains("项目用 Spring Boot") // 核心记忆
        .contains("归档条目A") // load 输出原样（截断契约在 store 侧钉死）
        .contains("[用户] hi") // 会话历史
        .contains("[助手] 你好");
    // 接口墙：buildContext 只经 load 取长期记忆，不直接碰归档区（recallByKeyword 未调用）
    verify(store).load();
    verifyNoMoreInteractions(store);
  }

  @Test
  @DisplayName("remember 委托 LongTermMemoryStore.append（content 与 scope 原样传递）")
  void rememberDelegatesToStore() {
    LongTermMemoryStore store = mock(LongTermMemoryStore.class);
    MemoryServiceImpl service = new MemoryServiceImpl(store);

    service.remember("值得记住的事", MemoryScope.CORE);

    verify(store).append("值得记住的事", MemoryScope.CORE);
    verifyNoMoreInteractions(store);
  }

  @Test
  @DisplayName("recall 委托 LongTermMemoryStore.recallByKeyword 并原样返回")
  void recallDelegatesToStore() {
    LongTermMemoryStore store = mock(LongTermMemoryStore.class);
    when(store.recallByKeyword("偏好")).thenReturn(List.of("条目一", "条目二"));
    MemoryServiceImpl service = new MemoryServiceImpl(store);

    assertThat(service.recall("偏好")).containsExactly("条目一", "条目二");
    verify(store).recallByKeyword("偏好");
    verifyNoMoreInteractions(store);
  }
}
