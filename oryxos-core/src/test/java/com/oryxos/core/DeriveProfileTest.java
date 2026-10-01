package com.oryxos.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * DeriveProfileTest（011 FR-1）——frontmatter 各字段正确映射 Profile；schedules 原样带进派生 Profile（定时来自 Agent
 * 的直接证据）。
 */
class DeriveProfileTest {

  private static final String FULL_FRONTMATTER =
      """
      ---
      name: ops-agent
      description: 运维助手
      identity:
        agent_name: 运维小欧
        prompt: 你是一个专业的运维助手
      provider:
        name: deepseek
        model: deepseek-chat
        temperature: 0.7
      tools:
        - read_file
        - shell
      mcp_servers:
        - github-mcp
      channels:
        - name: cli
      bootstrap:
        - AGENTS.md
      schedules:
        - id: weather-8am
          cron: "0 8 * * *"
          zone: Asia/Shanghai
          message: 生成今日天气和穿搭建议
      settings:
        max_iterations: 8
        max_history_turns: 15
      ---
      你是一个专业的运维助手。被触发时……
      """;

  @Test
  @DisplayName("合法 frontmatter：全字段映射 Profile")
  void parseFullFrontmatter(@TempDir Path tmp) throws Exception {
    Path agentDir = Files.createDirectories(tmp.resolve("ops-agent"));
    Files.writeString(agentDir.resolve("AGENT.md"), FULL_FRONTMATTER);

    Profile profile = new AgentLoader().deriveProfile(agentDir, Set.of("deepseek", "kimi"));

    assertThat(profile.name()).isEqualTo("ops-agent");
    assertThat(profile.description()).isEqualTo("运维助手");
    assertThat(profile.identity().agentName()).isEqualTo("运维小欧");
    assertThat(profile.identity().prompt()).contains("专业的运维助手");
    assertThat(profile.provider().name()).isEqualTo("deepseek");
    assertThat(profile.provider().model()).isEqualTo("deepseek-chat");
    assertThat(profile.provider().temperature()).isEqualTo(0.7);
    assertThat(profile.tools()).containsExactly("read_file", "shell");
    assertThat(profile.mcpServers()).containsExactly("github-mcp");
    assertThat(profile.channels()).containsExactly("cli");
    assertThat(profile.bootstrap()).containsExactly("AGENTS.md");
    assertThat(profile.settings().maxIterations()).isEqualTo(8);
    assertThat(profile.settings().maxHistoryTurns()).isEqualTo(15);
  }

  @Test
  @DisplayName("schedules 原样带进派生 Profile——定时来自 Agent 的直接证据")
  void schedulesCarriedIntoProfile(@TempDir Path tmp) throws Exception {
    Path agentDir = Files.createDirectories(tmp.resolve("sched-agent"));
    Files.writeString(
        agentDir.resolve("AGENT.md"),
        """
        ---
        name: sched-agent
        provider:
          name: deepseek
        schedules:
          - id: morning
            cron: "0 0 9 * * *"
            zone: Asia/Shanghai
            message: 早上好
          - id: evening
            cron: "0 30 18 * * *"
            zone: Asia/Shanghai
            message: 晚上好
        ---
        正文
        """);

    Profile profile = new AgentLoader().deriveProfile(agentDir, Set.of("deepseek"));

    assertThat(profile.schedules()).hasSize(2);
    assertThat(profile.schedules().get(0))
        .isEqualTo(new Profile.Schedule("morning", "0 0 9 * * *", "Asia/Shanghai", "早上好"));
    assertThat(profile.schedules().get(1))
        .isEqualTo(new Profile.Schedule("evening", "0 30 18 * * *", "Asia/Shanghai", "晚上好"));
  }

  @Test
  @DisplayName("settings 缺省：取默认值 10/20")
  void settingsDefaultsApply(@TempDir Path tmp) throws Exception {
    Path agentDir = Files.createDirectories(tmp.resolve("defaults"));
    Files.writeString(
        agentDir.resolve("AGENT.md"),
        "---\nname: defaults\nprovider:\n  name: deepseek\n---\n正文\n");

    Profile profile = new AgentLoader().deriveProfile(agentDir, Set.of("deepseek"));

    assertThat(profile.settings().maxIterations()).isEqualTo(10);
    assertThat(profile.settings().maxHistoryTurns()).isEqualTo(20);
  }
}
