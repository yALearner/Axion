package com.oryxos.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/**
 * profile show 命令（轻命令）——SnakeYAML 解析 AGENT.md frontmatter 打印概要（name/description/provider/
 * tools/bootstrap/settings；**api-key 占位不输出明文**——占位符原样展示，不解析环境变量）。
 */
@Command(name = "show", description = "查看 Agent 配置概要", mixinStandardHelpOptions = true)
public class ProfileShowCommand implements Runnable {

  @Parameters(index = "0", paramLabel = "<name>", description = "Agent 名")
  String name;

  @Override
  public void run() {
    Path agentFile = Path.of(".oryxos", "agents", name, "AGENT.md");
    if (!Files.isRegularFile(agentFile)) {
      throw new IllegalStateException("Agent 不存在: " + name);
    }
    try {
      String content = Files.readString(agentFile);
      Map<String, Object> frontmatter = frontmatterOf(content);
      if (frontmatter.isEmpty()) {
        System.out.println("（无 frontmatter）");
        return;
      }
      System.out.println("name: " + frontmatter.get("name"));
      System.out.println("description: " + frontmatter.get("description"));
      System.out.println("provider: " + frontmatter.get("provider"));
      System.out.println("tools: " + frontmatter.get("tools"));
      System.out.println("bootstrap: " + frontmatter.get("bootstrap"));
      System.out.println("settings: " + frontmatter.get("settings"));
    } catch (IOException e) {
      throw new IllegalStateException("Agent 读取失败: " + e.getMessage(), e);
    }
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> frontmatterOf(String content) {
    String[] lines = content.split("\\R", -1);
    if (lines.length < 3 || !lines[0].trim().equals("---")) {
      return Map.of();
    }
    StringBuilder yaml = new StringBuilder();
    for (int i = 1; i < lines.length; i++) {
      if (lines[i].trim().equals("---")) {
        break;
      }
      yaml.append(lines[i]).append(System.lineSeparator());
    }
    Object parsed = new Yaml().load(yaml.toString());
    if (!(parsed instanceof Map)) {
      return Map.of();
    }
    return (Map<String, Object>) parsed;
  }
}
