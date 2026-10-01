package com.oryxos.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;

/** tool 命令组（分组父命令，Picocli 嵌套结构件）——list 子命令的挂载点。 */
@Command(
    name = "tool",
    description = "Tool 查询",
    mixinStandardHelpOptions = true,
    subcommands = {ToolListCommand.class})
public class ToolCommand implements Runnable {

  @Override
  public void run() {
    CommandLine.usage(this, System.out);
  }
}
