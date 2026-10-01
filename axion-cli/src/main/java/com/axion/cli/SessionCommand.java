package com.oryxos.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;

/** session 命令组（分组父命令，Picocli 嵌套结构件）——list 子命令的挂载点。 */
@Command(
    name = "session",
    description = "会话查询",
    mixinStandardHelpOptions = true,
    subcommands = {SessionListCommand.class})
public class SessionCommand implements Runnable {

  @Override
  public void run() {
    CommandLine.usage(this, System.out);
  }
}
