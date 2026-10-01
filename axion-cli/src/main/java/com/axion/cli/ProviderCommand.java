package com.oryxos.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;

/** provider 命令组（分组父命令，Picocli 嵌套结构件）——list 子命令的挂载点。 */
@Command(
    name = "provider",
    description = "Provider 查询",
    mixinStandardHelpOptions = true,
    subcommands = {ProviderListCommand.class})
public class ProviderCommand implements Runnable {

  @Override
  public void run() {
    CommandLine.usage(this, System.out);
  }
}
