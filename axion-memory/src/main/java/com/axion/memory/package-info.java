/**
 * OryxOS Memory — 核心能力三：长期记忆.
 *
 * <p>Memory 三层统一门面，包含：
 *
 * <ul>
 *   <li>{@code MemoryService} — 统一门面，收口 Session + LongTermMemory
 *   <li>{@code LongTermMemoryStore} — 可插拔后端接口
 *       <ul>
 *         <li>MarkdownMemoryStore（默认，MEMORY.md 文件）
 *         <li>SqliteMemoryStore（memory_entries 表）
 *         <li>Mem0MemoryStore（自托管语义记忆）
 *       </ul>
 *   <li>{@code MemoryTools} — save_memory / recall_memory 内置 Tool
 * </ul>
 */
package com.oryxos.memory;
