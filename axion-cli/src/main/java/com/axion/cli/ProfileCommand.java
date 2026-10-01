package com.oryxos.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;

/** profile 命令组（分组父命令，Picocli 嵌套结构件）——list/create/show/delete 四个子命令的挂载点。 */
@Command(
    name = "profile",
    description = "Agent（Profile）管理",
    mixinStandardHelpOptions = true,
    subcommands = {
      ProfileListCommand.class,
      ProfileCreateCommand.class,
      ProfileShowCommand.class,
      ProfileDeleteCommand.class
    })
public class ProfileCommand implements Runnable {

  @Override
  public void run() {
    CommandLine.usage(this, System.out);
  }
}
