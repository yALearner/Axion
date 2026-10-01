package com.oryxos.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Types;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * LlmCallRepository 验收 harness——测试里执行手工 schema.sql 建表（不让 Hibernate 自动建）， 验证 llm_calls
 * 能存能读、success/error_message 两列真实存在。
 */
class LlmCallRepositoryTest {

  private static final String SCHEMA_FILE = "src/main/resources/schema.sql";

  @Test
  @DisplayName("手工 schema.sql 建表：可存可读，success/error_message 列真实存在")
  void schemaScriptCreatesUsableTable(@TempDir Path tmp) throws Exception {
    String jdbcUrl = "jdbc:sqlite:" + tmp.resolve("test.db");
    executeSchema(jdbcUrl);

    Set<String> columns = new HashSet<>();
    try (Connection conn = DriverManager.getConnection(jdbcUrl);
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery("PRAGMA table_info(llm_calls)")) {
      while (rs.next()) {
        columns.add(rs.getString("name"));
      }
    }
    assertThat(columns)
        .contains("success", "error_message", "session_id", "provider", "model", "created_at");

    try (Connection conn = DriverManager.getConnection(jdbcUrl);
        PreparedStatement ps =
            conn.prepareStatement(
                "INSERT INTO llm_calls (session_id, provider, model, prompt_tokens,"
                    + " completion_tokens, total_tokens, success, error_message,"
                    + " duration_ms, created_at) VALUES (?,?,?,?,?,?,?,?,?,?)")) {
      ps.setString(1, "s-1");
      ps.setString(2, "deepseek");
      ps.setString(3, "deepseek-chat");
      ps.setInt(4, 100);
      ps.setInt(5, 50);
      ps.setInt(6, 150);
      ps.setInt(7, 1);
      ps.setNull(8, Types.VARCHAR);
      ps.setLong(9, 1234L);
      ps.setString(10, "2026-08-30T12:00:00Z");
      ps.executeUpdate();
    }
    try (Connection conn = DriverManager.getConnection(jdbcUrl);
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery("SELECT * FROM llm_calls")) {
      assertThat(rs.next()).isTrue();
      assertThat(rs.getString("session_id")).isEqualTo("s-1");
      assertThat(rs.getString("provider")).isEqualTo("deepseek");
      assertThat(rs.getInt("success")).isEqualTo(1);
      assertThat(rs.getString("created_at")).isEqualTo("2026-08-30T12:00:00Z");
    }
  }

  @Test
  @DisplayName("失败行：success=0 + error_message 有值，可读回")
  void failureRowRoundtrip(@TempDir Path tmp) throws Exception {
    String jdbcUrl = "jdbc:sqlite:" + tmp.resolve("test.db");
    executeSchema(jdbcUrl);

    try (Connection conn = DriverManager.getConnection(jdbcUrl);
        PreparedStatement ps =
            conn.prepareStatement(
                "INSERT INTO llm_calls (session_id, provider, model, success, error_message,"
                    + " duration_ms, created_at) VALUES (?,?,?,?,?,?,?)")) {
      ps.setString(1, "s-2");
      ps.setString(2, "kimi");
      ps.setString(3, "moonshot-v1");
      ps.setInt(4, 0);
      ps.setString(5, "connect timeout");
      ps.setLong(6, 999L);
      ps.setString(7, "2026-08-30T12:01:00Z");
      ps.executeUpdate();
    }
    try (Connection conn = DriverManager.getConnection(jdbcUrl);
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery("SELECT * FROM llm_calls")) {
      assertThat(rs.next()).isTrue();
      assertThat(rs.getInt("success")).isEqualTo(0);
      assertThat(rs.getString("error_message")).isEqualTo("connect timeout");
    }
  }

  /** 测试与生产走同一份手工建表脚本。 */
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
