package com.oryxos.cli;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/** profile delete 命令（轻命令）——递归删除 Agent 目录并输出被删路径；不存在时清晰报错（实现级明确拍板口径）。 */
@Command(name = "delete", description = "删除 Agent 目录", mixinStandardHelpOptions = true)
public class ProfileDeleteCommand implements Runnable {

  @Parameters(index = "0", paramLabel = "<name>", description = "Agent 名")
  String name;

  @Override
  public void run() {
    Path agentDir = Path.of(".oryxos", "agents", name);
    if (!Files.exists(agentDir)) {
      throw new IllegalStateException("Agent 不存在: " + name);
    }
    try {
      Files.walkFileTree(
          agentDir,
          new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                throws IOException {
              Files.delete(file);
              return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc)
                throws IOException {
              Files.delete(dir);
              return FileVisitResult.CONTINUE;
            }
          });
      System.out.println("已删除 Agent [" + name + "]：" + agentDir);
    } catch (IOException e) {
      throw new IllegalStateException("Agent 删除失败: " + e.getMessage(), e);
    }
  }
}
