package com.oryxos.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.oryxos.core.MemoryScope;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * SqliteMemoryStore 验收 harness——手工 schema.sql 建表（坑八口径：测试与生产同一份脚本，classpath 直读 storage 的
 * schema.sql）；append→INSERT、load→CORE 全量 + ARCHIVAL 倒序 LIMIT（截断语义）、recall→LIKE 仅归档；语义与 Markdown
 * 档一致；FR-6 故障路径：库损坏 → 异常上抛不静默。
 */
class SqliteMemoryStoreTest {

  private JdbcTemplate jdbc;
  private SqliteMemoryStore store;

  @BeforeEach
  void setUp(@TempDir Path tempDir) throws Exception {
    DriverManagerDataSource dataSource =
        new DriverManagerDataSource("jdbc:sqlite:" + tempDir.resolve("test.db"));
    jdbc = new JdbcTemplate(dataSource);
    String schema =
        new String(
            SqliteMemoryStoreTest.class.getResourceAsStream("/schema.sql").readAllBytes(),
            StandardCharsets.UTF_8);
    for (String sql : schema.split(";", -1)) {
      if (!sql.isBlank()) {
        jdbc.execute(sql);
      }
    }
    store = new SqliteMemoryStore(dataSource);
  }

  @Test
  @DisplayName("append→INSERT：写入 memory_entries 表（content/scope/created_at 三列落库）")
  void appendInsertsRow() {
    store.append("值得记住", MemoryScope.CORE);

    Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM memory_entries", Integer.class);
    assertThat(count).isEqualTo(1);
    MapRow row =
        jdbc.queryForObject(
            "SELECT content, scope, created_at FROM memory_entries",
            (rs, rowNum) ->
                new MapRow(
                    rs.getString("content"), rs.getString("scope"), rs.getString("created_at")));
    assertThat(row.content()).isEqualTo("值得记住");
    assertThat(row.scope()).isEqualTo("CORE");
    assertThat(row.createdAt()).isNotNull(); // ISO-8601 TEXT 落库
  }

  @Test
  @DisplayName("load→CORE 全量 + ARCHIVAL 倒序 LIMIT（截断语义对应）")
  void loadCoreFullAndArchiveLimited() {
    store.append("核心条目A", MemoryScope.CORE);
    for (int i = 0; i < 250; i++) {
      store.append("归档流水 " + i, MemoryScope.ARCHIVAL);
    }

    String loaded = store.load();

    assertThat(loaded).contains("核心条目A"); // 核心区全量
    assertThat(loaded).contains("归档流水 249"); // 最近保留
    assertThat(loaded).doesNotContain("归档流水 0"); // 最旧被 LIMIT 裁掉
  }

  @Test
  @DisplayName("recall→LIKE 仅归档：核心区同关键词不命中（坑十八 SQL 形态）")
  void recallOnlySearchesArchive() {
    store.append("核心区的词Y", MemoryScope.CORE);
    store.append("归档区的词Y", MemoryScope.ARCHIVAL);

    assertThat(store.recallByKeyword("词Y")).hasSize(1);
    assertThat(store.recallByKeyword("词Y").get(0)).isEqualTo("归档区的词Y");
    assertThat(store.recallByKeyword("不存在")).isEmpty();
  }

  @Test
  @DisplayName("坑十五：append 后立刻 load 命中——每次查库不缓存")
  void appendImmediatelyVisible() {
    store.append("刚记的", MemoryScope.ARCHIVAL);

    assertThat(store.load()).contains("刚记的");
  }

  @Test
  @DisplayName("FR-6 故障路径：库损坏（表被删）→ load/recall 异常上抛不静默（快速失败，不返回空记忆）")
  void corruptedDatabaseFailsFast() {
    jdbc.execute("DROP TABLE memory_entries");

    assertThatThrownBy(store::load).isInstanceOf(DataAccessException.class);
    assertThatThrownBy(() -> store.recallByKeyword("x")).isInstanceOf(DataAccessException.class);
  }

  /** 只读行映射（测试内用，避免为断言引入实体依赖）。 */
  private record MapRow(String content, String scope, String createdAt) {}
}
