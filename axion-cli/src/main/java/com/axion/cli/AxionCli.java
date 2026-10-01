package com.axion.cli;

import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * Axion CLI — Picocli 命令行入口.
 *
 * <p>12 个子命令：init / status / chat / serve / gateway / profile list|create|show|delete / provider
 * list / tool list / session list.
 *
 * <p>commands that require Spring Boot context (chat, serve, gateway) start the full Spring
 * container; others operate directly on files for fast startup.
 */
@Command(
    name = "axion",
    description = "Axion — Java 原生的、企业私有可审计的 Agent 统一底座",
    mixinStandardHelpOptions = true,
    versionProvider = AxionCli.VersionProvider.class,
    subcommands = {
      InitCommand.class,
      StatusCommand.class,
      ChatCommand.class,
      ServeCommand.class,
      GatewayCommand.class,
      ProfileCommand.class,
      ProviderCommand.class,
      ToolCommand.class,
      SessionCommand.class
    })
public class AxionCli implements Callable<Integer> {

  @Override
  public Integer call() {
    // No subcommand → print usage
    CommandLine.usage(this, System.out);
    return 0;
  }

  public static void main(String[] args) {
    int exitCode = new CommandLine(new AxionCli()).execute(args);
    System.exit(exitCode);
  }

  /** Provides version information from the JAR manifest. */
  static class VersionProvider implements CommandLine.IVersionProvider {

    @Override
    public String[] getVersion() {
      String version = AxionCli.class.getPackage().getImplementationVersion();
      if (version == null) {
        version = "0.1.0-SNAPSHOT (dev)";
      }

      return new String[] {
        "",
        "  █████╗ ██╗  ██╗ ██╗  ██████╗ ███╗   ██╗",
        " ██╔══██╗╚██╗██╔╝ ██║ ██╔═══██╗ ████╗  ██║",
        " ███████║ ╚███╔╝  ██║ ██║   ██║ ██╔██╗ ██║",
        " ██╔══██║ ██╔██╗  ██║ ██║   ██║ ██║╚██╗██║",
        " ██║  ██║ ██╔╝ ██╗ ██║ ╚██████╔╝ ██║ ╚████║",
        " ╚═╝  ╚═╝ ╚═╝  ╚═╝ ╚═╝  ╚═════╝  ╚═╝  ╚═══╝",
        "",
        "  Axion v" + version,
        "  Java 原生的、企业私有可审计的 Agent 统一底座.",
        "  JDK: " + System.getProperty("java.version") + " | OS: " + System.getProperty("os.name"),
        ""
      };
    }
  }
}
