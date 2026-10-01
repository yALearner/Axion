package com.oryxos.channel.cli;

import com.oryxos.core.AgentService;
import com.oryxos.core.Session;
import com.oryxos.core.SessionManager;
import java.nio.charset.Charset;
import java.util.Scanner;

/**
 * CLI Channel——chat 交互循环实体（课件第 18 节 §三骨架：读—转交—打印的薄壳）。
 *
 * <p>通篇没有任何"Agent 智能"：读 stdin、写 stdout，维护当前 Session，每行输入交 {@link AgentService#process}，{@code
 * /quit} 退出（CLI 唯一"自己判断"的逻辑）。{@code --message} 非空时发单条消息后 退出（需求文档 §5.10）。
 *
 * <p>会话三元组：channel 固定 {@code "cli"}、user 取本机用户名（实现级明确拍板）、profileName 由命令传入—— session_id 拼接只在
 * SessionManager 一处（H4 不变量四），本类只提供三元组、不拼字符串。
 */
public final class CliChannel {

  /** CLI 渠道常量——三触发源各自只提供三元组（课件 §三：Web 传 "web"、定时传 "scheduler"）。 */
  public static final String CHANNEL = "cli";

  private final AgentService agentService;
  private final SessionManager sessionManager;

  public CliChannel(AgentService agentService, SessionManager sessionManager) {
    this.agentService = agentService;
    this.sessionManager = sessionManager;
  }

  /** 交互式对话；message 非空时单条处理后返回。 */
  public void chat(String profileName, String message) {
    Session session = sessionManager.getOrCreate(CHANNEL, currentUser(), profileName);
    if (message != null && !message.isBlank()) {
      System.out.println(agentService.process(session, message));
      return;
    }
    Scanner in = new Scanner(System.in, terminalCharset());
    while (true) {
      System.out.print("> ");
      if (!in.hasNextLine()) {
        break; // EOF（管道输入）正常退出
      }
      String line = in.nextLine();
      if ("/quit".equals(line.trim())) {
        break;
      }
      System.out.println(agentService.process(session, line));
    }
  }

  private String currentUser() {
    return System.getProperty("user.name");
  }

  /**
   * stdin 的编码由终端决定（Windows 中文控制台为 GBK、现代终端常为 UTF-8）——必须跟随终端实际编码， 硬编码 UTF-8 会把 GBK 终端输入的中文读成乱码（003
   * 实跑踩过）。交互时以 {@code System.console()} 的 charset 为准；stdin 被重定向（无 console）时回退系统默认编码。
   */
  // DefaultCharset：终端输入编码必须跟随终端；SystemConsoleNull：本 jar 跑在 JDK 21，
  // stdin 被重定向时 console() 仍可能为 null（JDK 22+ 才保证非空），保留兜底
  @SuppressWarnings({"DefaultCharset", "SystemConsoleNull"})
  private Charset terminalCharset() {
    java.io.Console console = System.console();
    return console == null ? Charset.defaultCharset() : console.charset();
  }
}
