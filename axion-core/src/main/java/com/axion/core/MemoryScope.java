package com.oryxos.core;

/**
 * 长期记忆分区（坑十七：写哪区由 Agent 显式声明，系统不猜）。
 *
 * <p>{@code CORE} = 核心记忆——始终在场、永不截断，每轮 prompt 完整注入（坑十六）；{@code ARCHIVAL} = 归档记忆—— 超阈值只裁这一区，按需经
 * {@code recall_memory} 检索（坑十八）。分区语义在 markdown 档落到 MEMORY.md 两个 header、在 sqlite 档落到
 * memory_entries.scope 列、在 mem0 档落到 metadata（FR-5）。
 */
public enum MemoryScope {
  CORE,
  ARCHIVAL
}
