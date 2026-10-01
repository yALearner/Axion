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
import java.sql.Types;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * NotifyChannelRepository 验收 harness——坑八口径：测试执行手工 schema.sql 建表（不让 Hibernate 自动建——
 * 否则测试绿了、生产跑真脚本列名对不上白测），验证 notify_channels 四列真实存在、可存可读、 name 主键唯一约束生效、description 可空。
 */
class NotifyChannelRepositoryTest {

  private static final String SCHEMA_FILE = "src/main/resources/schema.sql";

  @Test
  @DisplayName("手工 schema.sql 建表：name/type/url/description 四列真实存在，可存可读")
  void schemaScriptCreatesUsableTable(@TempDir Path tmp) throws Exception {
    String jdbcUrl = "jdbc:sqlite:" + tmp.resolve("test.db");
    executeSchema(jdbcUrl);

    Set<String> columns = new HashSet<>();
    try (Connection conn = DriverManager.getConnection(jdbcUrl);
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery("PRAGMA table_info(notify_channels)")) {
      while (rs.next()) {
        columns.add(rs.getString("name"));
      }
    }
    assertThat(columns).contains("name", "type", "url", "description");

    try (Connection conn = DriverManager.getConnection(jdbcUrl);
        PreparedStatement ps =
            conn.prepareStatement(
                "INSERT INTO notify_channels (name, type, url, description) VALUES (?,?,?,?)")) {
      ps.setString(1, "team-lark");
      ps.setString(2, "webhook");
      ps.setString(3, "https://open.feishu.cn/open-apis/bot/v2/hook/xxx");
      ps.setString(4, "团队群机器人");
      ps.executeUpdate();
    }
    try (Connection conn = DriverManager.getConnection(jdbcUrl);
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery("SELECT * FROM notify_channels")) {
      assertThat(rs.next()).isTrue();
      assertThat(rs.getString("name")).isEqualTo("team-lark");
      assertThat(rs.getString("type")).isEqualTo("webhook");
      assertThat(rs.getString("url")).isEqualTo("https://open.feishu.cn/open-apis/bot/v2/hook/xxx");
      assertThat(rs.getString("description")).isEqualTo("团队群机器人");
    }
  }

  @Test
  @DisplayName("name 主键唯一约束生效：重复注册同一渠道名被拒绝")
  void duplicateNameRejected(@TempDir Path tmp) throws Exception {
    String jdbcUrl = "jdbc:sqlite:" + tmp.resolve("test.db");
    executeSchema(jdbcUrl);

    insertChannel(jdbcUrl, "team-lark", "https://a.example.com/hook");
    assertThatThrownBy(() -> insertChannel(jdbcUrl, "team-lark", "https://b.example.com/hook"))
        .isInstanceOf(SQLException.class);
  }

  @Test
  @DisplayName("description 可空：不填描述的行可正常写入读回")
  void nullableDescription(@TempDir Path tmp) throws Exception {
    String jdbcUrl = "jdbc:sqlite:" + tmp.resolve("test.db");
    executeSchema(jdbcUrl);

    try (Connection conn = DriverManager.getConnection(jdbcUrl);
        PreparedStatement ps =
            conn.prepareStatement(
                "INSERT INTO notify_channels (name, type, url, description) VALUES (?,?,?,?)")) {
      ps.setString(1, "team-prod");
      ps.setString(2, "webhook");
      ps.setString(3, "https://open.feishu.cn/open-apis/bot/v2/hook/prod");
      ps.setNull(4, Types.VARCHAR);
      ps.executeUpdate();
    }
    try (Connection conn = DriverManager.getConnection(jdbcUrl);
        Statement stmt = conn.createStatement();
        ResultSet rs =
            stmt.executeQuery("SELECT * FROM notify_channels WHERE name = 'team-prod'")) {
      assertThat(rs.next()).isTrue();
      assertThat(rs.getString("description")).isNull();
    }
  }

  private void insertChannel(String jdbcUrl, String name, String url) throws Exception {
    try (Connection conn = DriverManager.getConnection(jdbcUrl);
        PreparedStatement ps =
            conn.prepareStatement(
                "INSERT INTO notify_channels (name, type, url, description) VALUES (?,?,?,?)")) {
      ps.setString(1, name);
      ps.setString(2, "webhook");
      ps.setString(3, url);
      ps.setNull(4, Types.VARCHAR);
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
