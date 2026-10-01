package com.oryxos.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import picocli.CommandLine.Command;

/**
 * profile list 命令（轻命令）——扫 `.oryxos/agents/` 子目录列名（宪法 IV：一个目录 = 一个 Agent，用户拍板口径； 课件示例的 profiles/
 * 目录表述不采用）。
 */
@Command(name = "list", description = "列出全部 Agent（Profile）", mixinStandardHelpOptions = true)
public class ProfileListCommand implements Runnable {

  @Override
  public void run() {
    Path agentsRoot = Path.of(".oryxos", "agents");
    if (!Files.isDirectory(agentsRoot)) {
      System.out.println("工作区未初始化（先执行 oryxos init）");
      return;
    }
    try (Stream<Path> entries = Files.list(agentsRoot)) {
      boolean any =
          entries
                  .filter(Files::isDirectory)
                  .sorted(Comparator.comparing(p -> String.valueOf(p.getFileName())))
                  .peek(p -> System.out.println(p.getFileName()))
                  .count()
              > 0;
      if (!any) {
        System.out.println("（无 Agent，用 oryxos profile create <name> 创建）");
      }
    } catch (IOException e) {
      throw new IllegalStateException("Agent 目录读取失败: " + e.getMessage(), e);
    }
  }
}
