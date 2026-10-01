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
 * ToolInvocationRepository 验收 harness——坑八回归：测试里执行手工 schema.sql 建表（不让 Hibernate 自动建——
 * 否则测试绿了、生产跑真脚本列名对不上白测），验证 tool_invocations 能存能读、success/error_message 两列真实存在。
 */
class ToolInvocationRepositoryTest {

  private static final String SCHEMA_FILE = "src/main/resources/schema.sql";

  @Test
  @DisplayName("手工 schema.sql 建表：可存可读，success/error_message 两列真实存在")
  void schemaScriptCreatesUsableTable(@TempDir Path tmp) throws Exception {
    String jdbcUrl = "jdbc:sqlite:" + tmp.resolve("test.db");
    executeSchema(jdbcUrl);

    Set<String> columns = new HashSet<>();
    try (Connection conn = DriverManager.getConnection(jdbcUrl);
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery("PRAGMA table_info(tool_invocations)")) {
      while (rs.next()) {
        columns.add(rs.getString("name"));
      }
    }
    assertThat(columns)
        .contains(
            "session_id",
            "tool_name",
            "input_json",
            "result_json",
            "success",
            "error_message",
            "duration_ms",
            "created_at");

    try (Connection conn = DriverManager.getConnection(jdbcUrl);
        PreparedStatement ps =
            conn.prepareStatement(
                "INSERT INTO tool_invocations (session_id, tool_name, input_json, result_json,"
                    + " success, error_message, duration_ms, created_at) VALUES"
                    + " (?,?,?,?,?,?,?,?)")) {
      ps.setString(1, "s-1");
      ps.setString(2, "http_get");
      ps.setString(3, "{\"url\":\"http://x\"}");
      ps.setString(4, "北京 22°C");
      ps.setInt(5, 1);
      ps.setNull(6, Types.VARCHAR);
      ps.setLong(7, 1234L);
      ps.setString(8, "2026-09-01T12:00:00Z");
      ps.executeUpdate();
    }
    try (Connection conn = DriverManager.getConnection(jdbcUrl);
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery("SELECT * FROM tool_invocations")) {
      assertThat(rs.next()).isTrue();
      assertThat(rs.getString("session_id")).isEqualTo("s-1");
      assertThat(rs.getString("tool_name")).isEqualTo("http_get");
      assertThat(rs.getString("input_json")).isEqualTo("{\"url\":\"http://x\"}");
      assertThat(rs.getString("result_json")).isEqualTo("北京 22°C");
      assertThat(rs.getInt("success")).isEqualTo(1);
      assertThat(rs.getString("created_at")).isEqualTo("2026-09-01T12:00:00Z");
    }
  }

  @Test
  @DisplayName("失败行：success=0 + error_message 有值，可读回（与 llm_calls 同口径）")
  void failureRowRoundtrip(@TempDir Path tmp) throws Exception {
    String jdbcUrl = "jdbc:sqlite:" + tmp.resolve("test.db");
    executeSchema(jdbcUrl);

    try (Connection conn = DriverManager.getConnection(jdbcUrl);
        PreparedStatement ps =
            conn.prepareStatement(
                "INSERT INTO tool_invocations (session_id, tool_name, success, error_message,"
                    + " duration_ms, created_at) VALUES (?,?,?,?,?,?)")) {
      ps.setString(1, "s-2");
      ps.setString(2, "shell");
      ps.setInt(3, 0);
      ps.setString(4, "connect timeout");
      ps.setLong(5, 999L);
      ps.setString(6, "2026-09-01T12:01:00Z");
      ps.executeUpdate();
    }
    try (Connection conn = DriverManager.getConnection(jdbcUrl);
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery("SELECT * FROM tool_invocations")) {
      assertThat(rs.next()).isTrue();
      assertThat(rs.getInt("success")).isEqualTo(0);
      assertThat(rs.getString("error_message")).isEqualTo("connect timeout");
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
