package com.oryxos.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 会话（最小契约，内存版）——对话历史累积容器。
 *
 * <p>sessions 表与 JPA 持久化归第 18 节；历史以框架无关的 {@link Message} 承载，可 JSON 序列化。每轮 LLM
 * 响应与工具结果按序累积进本容器（坑三：下一轮接得上、事后可审计）。
 *
 * <p>会话标识只在 {@link SessionManager} 内一处拼接（H4 不变量四）——构造器包私有，外部只能经 SessionManager 获得实例，保证没有第二条生成 id
 * 的路径。
 */
public final class Session {

  private final String id;
  private final String profileName;
  private final String channel;
  private final String userId;
  private final List<Message> messages = new ArrayList<>();

  Session(String id, String profileName, String channel, String userId) {
    this.id = id;
    this.profileName = profileName;
    this.channel = channel;
    this.userId = userId;
  }

  /** 追加一条消息到对话历史（用户消息 / LLM 响应 / 工具结果都走这里）。 */
  public void append(Message message) {
    messages.add(message);
  }

  public String id() {
    return id;
  }

  public String profileName() {
    return profileName;
  }

  public String channel() {
    return channel;
  }

  public String userId() {
    return userId;
  }

  /** 对话历史的不可变视图：不暴露可变列表的内部表示。 */
  public List<Message> messages() {
    return Collections.unmodifiableList(messages);
  }
}
