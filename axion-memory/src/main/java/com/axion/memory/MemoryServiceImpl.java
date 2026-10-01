package com.oryxos.memory;

import com.oryxos.core.LongTermMemoryStore;
import com.oryxos.core.MemoryScope;
import com.oryxos.core.MemoryService;
import com.oryxos.core.Message;
import com.oryxos.core.Session;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;

/**
 * {@link MemoryService} 统一门面实现（FR-1，落 oryxos-memory）。
 *
 * <p>buildContext 组装本会话的记忆上下文（技术方案 §4.2 第 2 部分）：长期记忆经 {@link LongTermMemoryStore#load()} 取得（核心区全量 +
 * 归档区截断后，截断契约在 store 侧钉死）、会话历史取自 SessionManager 管理的 Session 实例 （传入的 session 是 ReAct
 * 循环中的权威内存态——当前轮消息尚未落库，不能回查 SQLite）。remember/recall 直接委托 LongTermMemoryStore，失败异常上抛（FR-6 快速失败不静默）。
 */
public class MemoryServiceImpl implements MemoryService {

  private static final String MEMORY_HEADER = "## 长期记忆";
  private static final String HISTORY_HEADER = "## 会话历史";

  private final LongTermMemoryStore longTermStore;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "LongTermMemoryStore 为装配处注入的单例（接口墙委托对象，不可复制），仅本类只读使用、不暴露引用（004 WebhookNotifyAdapter"
              + " 同款先例）")
  public MemoryServiceImpl(LongTermMemoryStore longTermStore) {
    this.longTermStore = longTermStore;
  }

  @Override
  public String buildContext(Session session) {
    StringBuilder context = new StringBuilder(MEMORY_HEADER);
    context.append(System.lineSeparator()).append(longTermStore.load()); // 坑十五：每次重新读
    context.append(System.lineSeparator()).append(HISTORY_HEADER).append(System.lineSeparator());
    for (Message message : session.messages()) {
      String content = message.content();
      if (content == null || content.isBlank()) {
        continue;
      }
      context.append('[').append(roleLabel(message.role())).append("] ").append(content);
      context.append(System.lineSeparator());
    }
    return context.toString();
  }

  @Override
  public void remember(String content, MemoryScope scope) {
    longTermStore.append(content, scope);
  }

  @Override
  public List<String> recall(String keyword) {
    return longTermStore.recallByKeyword(keyword);
  }

  private String roleLabel(Message.MessageRole role) {
    return switch (role) {
      case USER -> "用户";
      case ASSISTANT -> "助手";
      case TOOL -> "工具";
      case SYSTEM -> "系统";
    };
  }
}
