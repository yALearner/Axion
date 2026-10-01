package com.oryxos.cli;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import picocli.CommandLine.Command;

/** status 命令（轻命令）——汇总：工作区存在性、Agent 数（agents 目录计数）、会话数（JDBC 计数，容错）、 数据库位置（研究 R6 口径）。 */
@Command(name = "status", description = "查看配置和运行状态", mixinStandardHelpOptions = true)
public class StatusCommand implements Runnable {

  @Override
  public void run() {
    Path workspace = Path.of(".oryxos");
    System.out.println(
        "工作区: "
            + (Files.isDirectory(workspace)
                ? "已初始化 " + workspace.toAbsolutePath()
                : "未初始化（先执行 oryxos init）"));

    Path agentsRoot = workspace.resolve("agents");
    long agentCount = 0;
    if (Files.isDirectory(agentsRoot)) {
      try (var entries = Files.list(agentsRoot)) {
        agentCount = entries.filter(Files::isDirectory).count();
      } catch (java.io.IOException ignored) {
        // 计数失败按 0 处理，不阻断
      }
    }
    System.out.println("Agent 数: " + agentCount);

    Path db = workspace.resolve("oryxos.db");
    System.out.println("数据库: " + db.toAbsolutePath());
    long sessionCount = -1;
    if (Files.exists(db)) {
      try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db);
          Statement stmt = conn.createStatement();
          ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM sessions")) {
        sessionCount = rs.next() ? rs.getLong(1) : 0;
      } catch (java.sql.SQLException ignored) {
        // 表未就绪等按未知处理
      }
    }
    System.out.println("会话数: " + (sessionCount >= 0 ? sessionCount : "（库或表未就绪）"));
  }
}
