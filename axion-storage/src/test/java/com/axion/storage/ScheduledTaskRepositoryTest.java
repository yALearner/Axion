package com.oryxos.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * scheduled_tasks / task_executions 验收 harness——坑八口径：测试执行手工 schema.sql 建表（不让 Hibernate 自动建——
 * 否则测试绿了、生产跑真脚本列名对不上白测），验证两表列真实存在、可存可读、task_id 主键唯一约束生效、可空列可空。
 */
class ScheduledTaskRepositoryTest {

  private static final String SCHEMA_FILE = "src/main/resources/schema.sql";

  @Test
  @DisplayName("手工 schema.sql 建表：scheduled_tasks 十列真实存在（照需求文档 DDL 骨架逐字）")
  void scheduledTasksTableHasTenColumns(@TempDir Path tmp) throws Exception {
    String jdbcUrl = "jdbc:sqlite:" + tmp.resolve("test.db");
    executeSchema(jdbcUrl);

    Set<String> columns = new HashSet<>();
    try (Connection conn = DriverManager.getConnection(jdbcUrl);
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery("PRAGMA table_info(scheduled_tasks)")) {
      while (rs.next()) {
        columns.add(rs.getString("name"));
      }
    }
    assertThat(columns)
        .contains(
            "task_id",
            "profile_name",
            "cron",
            "zone",
            "message",
            "enabled",
            "next_run_at",
            "last_run_at",
            "last_status",
            "run_count");
  }

  @Test
  @DisplayName("手工 schema.sql 建表：task_executions 七列真实存在（照需求文档 DDL 骨架逐字）")
  void taskExecutionsTableHasSevenColumns(@TempDir Path tmp) throws Exception {
    String jdbcUrl = "jdbc:sqlite:" + tmp.resolve("test.db");
    executeSchema(jdbcUrl);

    Set<String> columns = new HashSet<>();
    try (Connection conn = DriverManager.getConnection(jdbcUrl);
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery("PRAGMA table_info(task_executions)")) {
      while (rs.next()) {
        columns.add(rs.getString("name"));
      }
    }
    assertThat(columns)
        .contains(
            "id", "task_id", "session_id", "started_at", "success", "error_message", "duration_ms");
  }

  @Test
  @DisplayName("两表可存可读：状态行与执行历史写入后按预期读回（enabled 缺省 true、run_count 缺省 0）")
  void tablesRoundTrip(@TempDir Path tmp) throws Exception {
    String jdbcUrl = "jdbc:sqlite:" + tmp.resolve("test.db");
    executeSchema(jdbcUrl);

    try (Connection conn = DriverManager.getConnection(jdbcUrl);
        PreparedStatement ps =
            conn.prepareStatement(
                "INSERT INTO scheduled_tasks (task_id, profile_name, cron, zone, message) VALUES"
                    + " (?,?,?,?,?)")) {
      ps.setString(1, "weather-8am");
      ps.setString(2, "weather-agent");
      ps.setString(3, "0 0 8 * * *");
      ps.setString(4, "Asia/Shanghai");
      ps.setString(5, "生成今日天气和穿搭建议");
      ps.executeUpdate();
    }
    try (Connection conn = DriverManager.getConnection(jdbcUrl);
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery("SELECT * FROM scheduled_tasks")) {
      assertThat(rs.next()).isTrue();
      assertThat(rs.getString("task_id")).isEqualTo("weather-8am");
      assertThat(rs.getBoolean("enabled")).isTrue(); // 缺省 true
      assertThat(rs.getInt("run_count")).isZero(); // 缺省 0
      assertThat(rs.getString("last_status")).isNull(); // 未跑过
    }

    try (Connection conn = DriverManager.getConnection(jdbcUrl);
        PreparedStatement ps =
            conn.prepareStatement(
                "INSERT INTO task_executions (task_id, session_id, started_at, success) VALUES"
                    + " (?,?,?,?)")) {
      ps.setString(1, "weather-8am");
      ps.setString(2, "scheduler|scheduler|weather-agent");
      ps.setString(3, "2026-09-09T00:00:00Z");
      ps.setBoolean(4, true);
      ps.executeUpdate();
    }
    try (Connection conn = DriverManager.getConnection(jdbcUrl);
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery("SELECT * FROM task_executions")) {
      assertThat(rs.next()).isTrue();
      assertThat(rs.getLong("id")).isEqualTo(1); // AUTOINCREMENT 生效
      assertThat(rs.getBoolean("success")).isTrue();
      assertThat(rs.getString("error_message")).isNull();
    }
  }

  @Test
  @DisplayName("task_id 主键唯一约束生效：同 id 再注册被拒绝（跨 Profile 冲突由 registerAll 先行报错）")
  void duplicateTaskIdRejected(@TempDir Path tmp) throws Exception {
    String jdbcUrl = "jdbc:sqlite:" + tmp.resolve("test.db");
    executeSchema(jdbcUrl);

    insertTask(jdbcUrl, "dup-id", "a-agent");
    assertThatThrownBy(() -> insertTask(jdbcUrl, "dup-id", "b-agent"))
        .isInstanceOf(SQLException.class);
  }

  private void insertTask(String jdbcUrl, String taskId, String profileName) throws Exception {
    try (Connection conn = DriverManager.getConnection(jdbcUrl);
        PreparedStatement ps =
            conn.prepareStatement(
                "INSERT INTO scheduled_tasks (task_id, profile_name, cron, zone, message) VALUES"
                    + " (?,?,?,?,?)")) {
      ps.setString(1, taskId);
      ps.setString(2, profileName);
      ps.setString(3, "0 0 8 * * *");
      ps.setString(4, "Asia/Shanghai");
      ps.setString(5, "msg");
      ps.executeUpdate();
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
