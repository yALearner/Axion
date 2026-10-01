package com.oryxos.memory;

import com.oryxos.core.LongTermMemoryStore;
import com.oryxos.core.MemoryScope;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 长期记忆 sqlite 档（FR-4）：记忆按条入 {@code memory_entries} 表（schema.sql 手工增量，坑八口径——测试与生产同一份脚本）。
 * append→INSERT；load→CORE 全量 + ARCHIVAL 按 id 倒序 LIMIT {@value #ARCHIVE_LIMIT}
 * 条（截断语义对应，保留最近的、最旧的被裁掉）； recall→{@code LIKE '%keyword%'} 仅 ARCHIVAL（坑十八的 SQL 形态，通配符转义防注入式误配）。
 *
 * <p>复用已有 SQLite（{@code spring-jdbc} 已在依赖树，零新第三方依赖）；每次调用现查库不缓存（坑十五）；库损坏/不可读 → JdbcTemplate 的 {@code
 * DataAccessException} 自然上抛不静默（FR-6 快速失败，不返回空记忆、不自动降级换档）。纯类交付无组件注解（G4-C1 延续），装配处显式 {@code @Bean}。
 */
public class SqliteMemoryStore implements LongTermMemoryStore {

  /** 归档区最近条数上限——markdown 档 4000 字截断语义的 SQL 形态（千条级归档取最近 200 条注入 prompt）。 */
  private static final int ARCHIVE_LIMIT = 200;

  private static final String CORE_SCOPE = MemoryScope.CORE.name();
  private static final String ARCHIVAL_SCOPE = MemoryScope.ARCHIVAL.name();

  private final JdbcTemplate jdbc;

  public SqliteMemoryStore(DataSource dataSource) {
    this.jdbc = new JdbcTemplate(dataSource);
  }

  @Override
  public void append(String content, MemoryScope scope) {
    jdbc.update(
        "INSERT INTO memory_entries (content, scope, created_at) VALUES (?, ?, ?)",
        content,
        scope.name(),
        Instant.now().toString()); // ISO-8601 TEXT，InstantTextConverter 同口径
  }

  @Override
  public String load() {
    List<String> core =
        jdbc.queryForList(
            "SELECT content FROM memory_entries WHERE scope = ? ORDER BY id",
            String.class,
            CORE_SCOPE);
    // 倒序取最近 N 条再翻转回正序：与 markdown 档"最新在尾部"的口径一致
    List<String> archive =
        jdbc.queryForList(
            "SELECT content FROM memory_entries WHERE scope = ? ORDER BY id DESC LIMIT ?",
            String.class,
            ARCHIVAL_SCOPE,
            ARCHIVE_LIMIT);
    Collections.reverse(archive);
    return MarkdownMemoryStore.CORE_HEADER
        + System.lineSeparator()
        + String.join(System.lineSeparator(), core)
        + System.lineSeparator()
        + MarkdownMemoryStore.ARCHIVE_HEADER
        + System.lineSeparator()
        + String.join(System.lineSeparator(), archive);
  }

  @Override
  public List<String> recallByKeyword(String keyword) {
    if (keyword == null || keyword.isBlank()) {
      return List.of();
    }
    // LIKE 通配符转义：用户关键词里的 %/_ 按字面匹配（不变成模式注入）
    String escaped = keyword.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    return jdbc.queryForList(
        "SELECT content FROM memory_entries WHERE scope = ? AND content LIKE ? ESCAPE '\\' ORDER BY"
            + " id",
        String.class,
        ARCHIVAL_SCOPE,
        "%" + escaped + "%");
  }
}
