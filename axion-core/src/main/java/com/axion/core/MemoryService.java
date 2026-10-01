package com.oryxos.core;

import java.util.List;

/**
 * 记忆统一门面（FR-1，依赖倒置端口——001 {@link LlmGateway} 先例）。
 *
 * <p>对 ReAct 循环只暴露这一个接口：内部把会话记忆（{@link SessionManager}，SQLite 持久化）与长期记忆 （{@link
 * LongTermMemoryStore} 可插拔后端）收口成三层记忆，上层不需要分别问两个地方（技术方案 §5.1 架构调整说明）。实现落 oryxos-memory，core
 * 只承载接口——PromptBuilder 在 core 调用它，core←memory 反向依赖不成立。
 */
public interface MemoryService {

  /**
   * 组装本会话的记忆上下文（技术方案 §4.2 第 2 部分：Memory 注入）——会话历史（SessionManager 管理的 Session 消息）+ 长期记忆（{@link
   * LongTermMemoryStore#load()}：核心区全量 + 归档区截断后），供 PromptBuilder 拼入 system
   * prompt。每次重新读取不缓存（坑十五联动：save_memory 下一轮立刻可见）。
   */
  String buildContext(Session session);

  /** 写入长期记忆（save_memory 的落点）；scope 由 Agent 显式声明（坑十七）。 */
  void remember(String content, MemoryScope scope);

  /** 关键词检索长期记忆归档区（recall_memory 的落点）；未命中返回空列表。 */
  List<String> recall(String keyword);
}
