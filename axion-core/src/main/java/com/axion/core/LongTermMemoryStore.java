package com.oryxos.core;

import java.util.List;

/**
 * 长期记忆可插拔后端接口（FR-2）——长期记忆读写契约与具体存储解耦，换后端只改 {@code oryxos.memory.backend} 一行配置，本接口之上（PromptBuilder /
 * MemoryTools / ReActLoop）零改动。
 *
 * <p><strong>四条行为契约（全实现共用，参数化契约测试钉死）：</strong>
 *
 * <ol>
 *   <li><strong>不缓存（坑十五）</strong>：每次调用重新读取底层——save_memory 写入后下一轮 load/recall 立即可见；
 *   <li><strong>核心记忆永不截断（坑十六）</strong>：{@code load()} 的核心区一字不少；超阈值截断只裁归档区尾部；
 *   <li><strong>scope 显式（坑十七）</strong>：写核心还是归档由调用方经 {@link MemoryScope} 显式声明，实现层不猜；
 *   <li><strong>检索只搜归档（坑十八）</strong>：{@link #recallByKeyword(String)} 只检索归档区，不做复杂化。
 * </ol>
 *
 * <p><strong>后端故障快速失败</strong>（FR-6）：append/load/recallByKeyword 遇后端不可用 MUST 异常上抛明确报错， MUST NOT
 * 静默返回空结果、MUST NOT 自动降级换档——静默失忆会让 Agent 带着错误认知作答，比报错更危险。
 */
public interface LongTermMemoryStore {

  /** 追加一条长期记忆到指定分区；失败异常上抛（由 ToolExecutor 审计 success=false），不静默"已记住"。 */
  void append(String content, MemoryScope scope);

  /** 读取全部长期记忆：核心区完整返回 + 归档区超阈值截断后返回；每次重新读不缓存（坑十五）。 */
  String load();

  /** 关键词检索——只在归档区做朴素包含匹配（坑十八）；未命中返回空列表，不抛异常。 */
  List<String> recallByKeyword(String keyword);
}
