package com.oryxos.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/**
 * profile create 命令（轻命令）——生成 `agents/<name>/AGENT.md` 模板（与 InitCommand 默认模板同口径，name 替换）；
 * 已存在时提示不覆盖（幂等）。
 */
@Command(
    name = "create",
    description = "创建新 Agent（生成 AGENT.md 模板）",
    mixinStandardHelpOptions = true)
public class ProfileCreateCommand implements Runnable {

  @Parameters(index = "0", paramLabel = "<name>", description = "Agent 名")
  String name;

  @Override
  public void run() {
    Path agentDir = Path.of(".oryxos", "agents", name);
    Path agentFile = agentDir.resolve("AGENT.md");
    if (Files.exists(agentFile)) {
      System.out.println("Agent [" + name + "] 已存在，未覆盖");
      return;
    }
    try {
      Files.createDirectories(agentDir);
      Files.writeString(agentFile, templateFor(name));
      System.out.println("已创建 Agent [" + name + "]：.oryxos/agents/" + name + "/AGENT.md");
    } catch (IOException e) {
      throw new IllegalStateException("Agent 创建失败: " + e.getMessage(), e);
    }
  }

  private String templateFor(String agentName) {
    return "---\n"
        + "name: "
        + agentName
        + "\n"
        + "description: "
        + agentName
        + "\n"
        + "identity:\n"
        + "  agent_name: "
        + agentName
        + "\n"
        + "  prompt: 你是一个专业的企业助手，回答严谨、简洁、用中文。\n"
        + "provider:\n"
        + "  name: deepseek\n"
        + "  model: deepseek-chat\n"
        + "  temperature: 0.7\n"
        + "  api_key: ${DEEPSEEK_API_KEY}\n"
        + "tools: []\n"
        + "bootstrap:\n"
        + "  - AGENTS.md\n"
        + "  - SOUL.md\n"
        + "  - USER.md\n"
        + "settings:\n"
        + "  max_iterations: 10\n"
        + "  max_history_turns: 20\n"
        + "---\n"
        + "这里是 "
        + agentName
        + " 的任务指令正文。按需改写本文件后重新启动即可生效。\n";
  }
}
