package com.oryxos.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import picocli.CommandLine.Command;

/**
 * init 命令（轻命令，不启动 Spring）——幂等初始化 `.oryxos/` 工作区（技术方案 §8.1 目录树，用户拍板口径）： 已存在的目录与文件一律不覆盖（需求文档
 * §5.1）；同时生成默认 Agent `agents/default/AGENT.md` 模板 （provider=deepseek + ${DEEPSEEK_API_KEY} 占位，001
 * 校验口径：引用必须命中全局层）。
 */
@Command(name = "init", description = "初始化 .oryxos 工作区（幂等）", mixinStandardHelpOptions = true)
public class InitCommand implements Runnable {

  static final String WORKSPACE = ".oryxos";

  private static final String DEFAULT_AGENT_TEMPLATE =
      """
      ---
      name: default
      description: 默认 Agent
      identity:
        agent_name: OryxOS
        prompt: 你是一个专业的企业助手，回答严谨、简洁、用中文。
      provider:
        name: deepseek
        model: deepseek-chat
        temperature: 0.7
        api_key: ${DEEPSEEK_API_KEY}
      tools: []
      bootstrap:
        - AGENTS.md
        - SOUL.md
        - USER.md
      settings:
        max_iterations: 10
        max_history_turns: 20
      ---
      这里是 default Agent 的任务指令正文。按需改写本文件后重新启动即可生效。
      """;

  @Override
  public void run() {
    try {
      initWorkspace(Path.of(WORKSPACE));
      System.out.println("已初始化 .oryxos 工作区（幂等：已存在内容未覆盖）");
    } catch (IOException e) {
      throw new IllegalStateException("工作区初始化失败: " + e.getMessage(), e);
    }
  }

  /** 建目录树 + 写模板；全部只补不覆盖。 */
  private void initWorkspace(Path workspace) throws IOException {
    Files.createDirectories(workspace.resolve("agents"));
    Files.createDirectories(workspace.resolve("skills"));
    Files.createDirectories(workspace.resolve("memory"));
    Files.createDirectories(workspace.resolve("sessions"));
    Files.createDirectories(workspace.resolve("logs"));

    writeIfAbsent(workspace.resolve("memory/MEMORY.md"), "# 长期记忆\n\n## 核心记忆\n\n## 归档记忆\n");
    writeIfAbsent(workspace.resolve("mcp_servers.yaml"), "# MCP server 配置（第 20 节起使用）\n");
    writeIfAbsent(workspace.resolve("AGENTS.md"), "# 项目级 Agent 行为说明\n回答严谨、简洁、用中文。\n");
    writeIfAbsent(workspace.resolve("SOUL.md"), "# 人格\n你是一位亲切又专业的助手。\n");
    writeIfAbsent(workspace.resolve("USER.md"), "# 用户偏好\n（只读：Agent 不写本文件）\n");
    // 默认 Agent：先建父目录再写模板（幂等：目录已存在时 createDirectories 不覆盖）
    Path defaultAgent = workspace.resolve("agents/default");
    Files.createDirectories(defaultAgent);
    writeIfAbsent(defaultAgent.resolve("AGENT.md"), DEFAULT_AGENT_TEMPLATE);
  }

  private void writeIfAbsent(Path file, String content) throws IOException {
    if (!Files.exists(file)) {
      Files.writeString(file, content);
    }
  }
}
