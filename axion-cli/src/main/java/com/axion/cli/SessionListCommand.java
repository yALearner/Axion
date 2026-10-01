package com.oryxos.cli;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import picocli.CommandLine.Command;

/**
 * session list 命令（轻命令，不启动 Spring）——JDBC 直读 `.oryxos/oryxos.db` 的 sessions 表输出概要
 * （session_id/profile/channel/最后活跃时间）；库或表不存在时输出提示不崩溃（研究 R1 口径）。
 */
@Command(name = "list", description = "列出会话历史概要", mixinStandardHelpOptions = true)
public class SessionListCommand implements Runnable {

  private static final String DB = ".oryxos/oryxos.db";

  @Override
  public void run() {
    Path db = Path.of(DB);
    if (!Files.exists(db)) {
      System.out.println("暂无会话数据（先执行 oryxos init / 跑一次 chat）");
      return;
    }
    try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + DB);
        Statement stmt = conn.createStatement();
        ResultSet rs =
            stmt.executeQuery(
                "SELECT session_id, profile_name, channel, last_active_at FROM sessions"
                    + " ORDER BY last_active_at DESC")) {
      boolean any = false;
      while (rs.next()) {
        any = true;
        System.out.printf(
            "%s | profile=%s | channel=%s | last_active=%s%n",
            rs.getString("session_id"),
            rs.getString("profile_name"),
            rs.getString("channel"),
            rs.getString("last_active_at"));
      }
      if (!any) {
        System.out.println("暂无会话数据（先执行 oryxos init / 跑一次 chat）");
      }
    } catch (java.sql.SQLException e) {
      System.out.println("暂无会话数据（库或表尚未就绪，先执行 oryxos init / 跑一次 chat）");
    }
  }
}
