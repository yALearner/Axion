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
 * SessionRepository 验收 harness——坑八口径：测试执行手工 schema.sql 建表（不让 Hibernate 自动建），验证 sessions
 * 表可存可读、messages_json（含 toolCall 嵌套）回读完整、模拟"重启"（新建连接重查）历史还在。
 */
class SessionRepositoryTest {

  private static final String SCHEMA_FILE = "src/main/resources/schema.sql";

  /** 含 toolCall/toolResult 嵌套结构的消息历史（Jackson 序列化的真实形态）。 */
  private static final String MESSAGES_JSON =
      "[{\"role\":\"USER\",\"content\":\"查天气\",\"toolCalls\":[],\"toolResults\":[]},"
          + "{\"role\":\"ASSISTANT\",\"content\":\"\",\"toolCalls\":[{\"id\":\"call-1\",\"name\":\"http_get\",\"arguments\":\"{}\"}],\"toolResults\":[]},{\"role\":\"TOOL\",\"content\":\"北京"
          + " 22°C\",\"toolCalls\":[],\"toolResults\":[{\"toolCallId\":\"call-1\",\"content\":\"北京"
          + " 22°C\"}]}]";

  @Test
  @DisplayName("手工 schema.sql 建表：sessions 可存可读，九列真实存在")
  void schemaScriptCreatesUsableTable(@TempDir Path tmp) throws Exception {
    String jdbcUrl = "jdbc:sqlite:" + tmp.resolve("test.db");
    executeSchema(jdbcUrl);

    Set<String> columns = new HashSet<>();
    try (Connection conn = DriverManager.getConnection(jdbcUrl);
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery("PRAGMA table_info(sessions)")) {
      while (rs.next()) {
        columns.add(rs.getString("name"));
      }
    }
    assertThat(columns)
        .contains(
            "session_id",
            "profile_name",
            "channel",
            "user_id",
            "messages_json",
            "status",
            "created_at",
            "last_active_at",
            "archived_at");

    try (Connection conn = DriverManager.getConnection(jdbcUrl);
        PreparedStatement ps =
            conn.prepareStatement(
                "INSERT INTO sessions (session_id, profile_name, channel, user_id, messages_json,"
                    + " status, created_at, last_active_at, archived_at) VALUES"
                    + " (?,?,?,?,?,?,?,?,?)")) {
      ps.setString(1, "cli|wang|default");
      ps.setString(2, "default");
      ps.setString(3, "cli");
      ps.setString(4, "wang");
      ps.setString(5, MESSAGES_JSON);
      ps.setString(6, "active");
      ps.setString(7, "2026-09-02T12:00:00Z");
      ps.setString(8, "2026-09-02T12:05:00Z");
      ps.setNull(9, java.sql.Types.VARCHAR);
      ps.executeUpdate();
    }
    try (Connection conn = DriverManager.getConnection(jdbcUrl);
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery("SELECT * FROM sessions")) {
      assertThat(rs.next()).isTrue();
      assertThat(rs.getString("session_id")).isEqualTo("cli|wang|default");
      assertThat(rs.getString("profile_name")).isEqualTo("default");
      assertThat(rs.getString("channel")).isEqualTo("cli");
      assertThat(rs.getString("user_id")).isEqualTo("wang");
      assertThat(rs.getString("status")).isEqualTo("active");
      // messages_json 回读完整：含 toolCall/toolResult 嵌套结构
      String messages = rs.getString("messages_json");
      assertThat(messages).contains("http_get").contains("call-1").contains("北京 22°C");
      assertThat(rs.getString("created_at")).isEqualTo("2026-09-02T12:00:00Z");
      assertThat(rs.getString("archived_at")).isNull();
    }
  }

  @Test
  @DisplayName("模拟重启：写入后新建连接重查，历史还在（跨重启恢复）")
  void historySurvivesRestart(@TempDir Path tmp) throws Exception {
    String jdbcUrl = "jdbc:sqlite:" + tmp.resolve("test.db");
    executeSchema(jdbcUrl);

    try (Connection conn = DriverManager.getConnection(jdbcUrl);
        PreparedStatement ps =
            conn.prepareStatement(
                "INSERT INTO sessions (session_id, profile_name, channel, user_id, messages_json,"
                    + " status, created_at, last_active_at) VALUES (?,?,?,?,?,?,?,?)")) {
      ps.setString(1, "cli|wang|default");
      ps.setString(2, "default");
      ps.setString(3, "cli");
      ps.setString(4, "wang");
      ps.setString(5, MESSAGES_JSON);
      ps.setString(6, "active");
      ps.setString(7, "2026-09-02T12:00:00Z");
      ps.setString(8, "2026-09-02T12:05:00Z");
      ps.executeUpdate();
    }
    // "重启"：全新连接重新查询
    try (Connection conn = DriverManager.getConnection(jdbcUrl);
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery("SELECT * FROM sessions")) {
      assertThat(rs.next()).isTrue();
      assertThat(rs.getString("messages_json")).contains("北京 22°C");
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
