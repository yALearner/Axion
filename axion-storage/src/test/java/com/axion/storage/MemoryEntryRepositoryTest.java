package com.oryxos.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * MemoryEntryRepository 验收 harness（005 storage 先例：每实体一 RepositoryTest）——坑八回归：测试里执行手工 schema.sql
 * 建表（不让 Hibernate 自动建——否则测试绿了、生产跑真脚本列名对不上白测），验证 memory_entries 能存能读、scope 列真实存在 （与 MEMORY.md
 * 两区块一一对应的分区语义）。
 */
class MemoryEntryRepositoryTest {

  private static final String SCHEMA_FILE = "src/main/resources/schema.sql";

  @Test
  @DisplayName("手工 schema.sql 建表：memory_entries 可存可读，content/scope/created_at 三列真实存在")
  void schemaScriptCreatesUsableTable(@TempDir Path tmp) throws Exception {
    String jdbcUrl = "jdbc:sqlite:" + tmp.resolve("test.db");
    executeSchema(jdbcUrl);

    Set<String> columns = new HashSet<>();
    try (Connection conn = DriverManager.getConnection(jdbcUrl);
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery("PRAGMA table_info(memory_entries)")) {
      while (rs.next()) {
        columns.add(rs.getString("name"));
      }
    }
    assertThat(columns).contains("id", "content", "scope", "created_at");

    try (Connection conn = DriverManager.getConnection(jdbcUrl);
        PreparedStatement ps =
            conn.prepareStatement(
                "INSERT INTO memory_entries (content, scope, created_at) VALUES (?,?,?)")) {
      ps.setString(1, "项目用 Spring Boot");
      ps.setString(2, "CORE");
      ps.setString(3, "2026-09-06T10:00:00Z");
      ps.executeUpdate();
    }
    try (Connection conn = DriverManager.getConnection(jdbcUrl);
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery("SELECT * FROM memory_entries")) {
      assertThat(rs.next()).isTrue();
      assertThat(rs.getString("content")).isEqualTo("项目用 Spring Boot");
      assertThat(rs.getString("scope")).isEqualTo("CORE");
      assertThat(rs.getString("created_at")).isEqualTo("2026-09-06T10:00:00Z");
    }
  }

  @Test
  @DisplayName("归档条目：scope=ARCHIVAL 落库并可按分区检索（分区语义与 MEMORY.md 两区块一一对应）")
  void archivalEntryRoundtrip(@TempDir Path tmp) throws Exception {
    String jdbcUrl = "jdbc:sqlite:" + tmp.resolve("test.db");
    executeSchema(jdbcUrl);

    try (Connection conn = DriverManager.getConnection(jdbcUrl);
        PreparedStatement ps =
            conn.prepareStatement(
                "INSERT INTO memory_entries (content, scope, created_at) VALUES (?,?,?)")) {
      ps.setString(1, "归档流水");
      ps.setString(2, "ARCHIVAL");
      ps.setString(3, "2026-09-06T11:00:00Z");
      ps.executeUpdate();
    }
    try (Connection conn = DriverManager.getConnection(jdbcUrl);
        Statement stmt = conn.createStatement();
        ResultSet rs =
            stmt.executeQuery("SELECT content FROM memory_entries WHERE scope = 'ARCHIVAL'")) {
      assertThat(rs.next()).isTrue();
      assertThat(rs.getString("content")).isEqualTo("归档流水");
    }
  }

  /** 测试与生产走同一份手工建表脚本（坑八）。 */
  private void executeSchema(String jdbcUrl) throws Exception {
    String schema = Files.readString(Path.of(SCHEMA_FILE));
    try (Connection conn = DriverManager.getConnection(jdbcUrl);
        Statement stmt = conn.createStatement()) {
      for (String sql : schema.split(";", -1)) {
        if (!sql.isBlank()) {
          stmt.execute(sql);
        }
      }
    }
  }
}
