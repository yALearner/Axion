package com.oryxos.storage;

import org.springframework.data.jpa.repository.JpaRepository;

/** memory_entries 表访问（sqlite 档）。核心阶段由 SqliteMemoryStore 消费写入与查询；管理端点归 Web Service 节直接消费。 */
public interface MemoryEntryRepository extends JpaRepository<MemoryEntry, Long> {}
