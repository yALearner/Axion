package com.axion.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.axion.storage.SessionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

/** PromptBuilder 验收 harness——四部分顺序、坑二截断、日期时间、记忆段跳过、工具列表 Function Calling 格式。 */
class PromptBuilderTest {

  private final ToolSchemaAdapter adapter = new ToolSchemaAdapter(new ObjectMapper());

  private static AxionTool toolNamed(String name) {
    return new AxionTool() {
      @Override
      public String getName() {
        return name;
      }

      @Override
      public String getDescription() {
        return name + " 的描述";
      }

      @Override
      public JsonSchema getInputSchema() {
        return new JsonSchema(Map.of("type", "object"));
      }

      @Override
      public ToolResult execute(com.fasterxml.jackson.databind.JsonNode input) {
        return ToolResult.success("ok");
      }
    };
  }

  private static Profile profileWithBootstrapAndTools(List<String> bootstrap, List<String> tools) {
    return new Profile(
        "ops-agent",
        null,
        new Profile.Identity("运维小欧", "你是一个专业运维助手"),
        new Profile.ProviderRef("deepseek", null, null),
        tools,
        List.of(),
        List.of(),
        List.of(),
        bootstrap,
        new Profile.Settings(10, 20));
  }

  /** 003 起 SessionManager 为 JPA 版（构造注入仓储）；测试用 mock 仓储 + 真 ObjectMapper。 */
  private SessionManager newSessionManager() {
    SessionRepository repository = mock(SessionRepository.class);
    when(repository.findById(org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(java.util.Optional.empty());
    return new SessionManager(repository, new ObjectMapper());
  }

  private PromptBuilder builder(Path workspace, List<AxionTool> tools) {
    // 011 改造点适配：ContextLoader 现注入 AGENT.md 正文（FR-2）——fixture 补 agent 目录（frontmatter-only，正文为空）
    writeAgentDir(workspace);
    Map<String, AxionTool> toolSet = new java.util.HashMap<>();
    for (AxionTool t : tools) {
      toolSet.put(t.getName(), t);
    }
    MemoryService memoryService = mock(MemoryService.class);
    when(memoryService.buildContext(org.mockito.ArgumentMatchers.any(Session.class)))
        .thenReturn("## 长期记忆\n记忆上下文: 核心条目测试");
    return new PromptBuilder(new ContextLoader(workspace), adapter, toolSet, memoryService);
  }

  /** 011 改造点适配：建 agents/ops-agent/AGENT.md（frontmatter-only 且无尾换行：正文为空）。 */
  private static void writeAgentDir(Path workspace) {
    try {
      Path agentDir =
          java.nio.file.Files.createDirectories(workspace.resolve("agents").resolve("ops-agent"));
      java.nio.file.Files.writeString(agentDir.resolve("AGENT.md"), "---\nname: ops-agent\n---");
    } catch (java.io.IOException e) {
      throw new IllegalStateException("fixture 创建失败", e);
    }
  }

  @Test
  @DisplayName("四部分按序：system（角色+引导+日期时间）→ 历史 → 工具；system 末尾附当前日期时间")
  void fourSectionsInOrderWithDateAtSystemEnd(@TempDir Path workspace) throws Exception {
    Files.writeString(workspace.resolve("AGENTS.md"), "# 引导内容A");
    Files.writeString(workspace.resolve("SOUL.md"), "# 引导内容B");
    Session session = newSessionManager().getOrCreate("cli", "alice", "ops-agent");
    session.append(Message.user("hi"));
    session.append(Message.assistant("你好"));

    Prompt prompt =
        builder(workspace, List.of(toolNamed("http_get")))
            .build(
                session,
                profileWithBootstrapAndTools(List.of("AGENTS.md", "SOUL.md"), List.of("http_get")));

    List<org.springframework.ai.chat.messages.Message> instructions = prompt.getInstructions();
    assertThat(instructions).hasSize(3);
    assertThat(instructions.get(0)).isInstanceOf(SystemMessage.class);
    String system = instructions.get(0).getText();
    assertThat(system)
        .contains("你是一个专业运维助手") // ① 角色设定
        .contains("# 引导内容A") // ① Bootstrap 内容
        .contains("# 引导内容B");
    assertThat(system.indexOf("你是一个专业运维助手")).isLessThan(system.indexOf("# 引导内容A"));
    assertThat(system.indexOf("# 引导内容A")).isLessThan(system.indexOf("# 引导内容B"));
    assertThat(system.indexOf("# 引导内容B")).isLessThan(system.indexOf("当前日期时间:")); // ① 日期在 system 末尾
    String[] lines = system.trim().split("\\R", -1);
    assertThat(lines[lines.length - 1]).startsWith("当前日期时间:"); // 当前日期时间确实在最末尾
    assertThat(instructions.get(1)).isInstanceOf(UserMessage.class);
    assertThat(instructions.get(2)).isInstanceOf(AssistantMessage.class);
  }

  @Test
  @DisplayName("坑二回归：历史超 N 轮被截断，且不切断一轮内的 tool 调用链")
  void historyTruncatedWithoutSplittingToolChains(@TempDir Path workspace) throws Exception {
    Session session = newSessionManager().getOrCreate("cli", "alice", "ops-agent");
    // 第 1 轮：带 tool 调用链（截断后应整体丢弃）
    session.append(Message.user("第1轮"));
    session.append(assistantWithToolCall("call-1"));
    session.append(toolResultMessage("call-1", "r1"));
    // 第 2 轮：带 tool 调用链（保留轮，tool 链必须完整保留）
    session.append(Message.user("第2轮"));
    session.append(assistantWithToolCall("call-2"));
    session.append(toolResultMessage("call-2", "r2"));
    // 第 3~21 轮：普通轮
    for (int i = 3; i <= 21; i++) {
      session.append(Message.user("第" + i + "轮"));
      session.append(Message.assistant("答" + i));
    }

    Prompt prompt =
        builder(workspace, List.of())
            .build(session, profileWithBootstrapAndTools(List.of(), List.of()));

    List<org.springframework.ai.chat.messages.Message> instructions = prompt.getInstructions();
    assertThat(instructions.get(0)).isInstanceOf(SystemMessage.class);
    List<org.springframework.ai.chat.messages.Message> history =
        instructions.subList(1, instructions.size());
    long userCount = history.stream().filter(m -> m instanceof UserMessage).count();
    assertThat(userCount).isEqualTo(20); // 只留最近 20 轮（默认）
    List<String> userTexts =
        history.stream().filter(m -> m instanceof UserMessage).map(m -> m.getText()).toList();
    assertThat(userTexts).doesNotContain("第1轮"); // 第 1 轮整体丢弃
    assertThat(userTexts).contains("第2轮"); // 第 2 轮保留
    // 保留轮的 tool 链完整：r2 的 TOOL 消息仍在（且只有它——第 1 轮已整体丢弃）
    List<String> toolData =
        history.stream()
            .filter(m -> m instanceof ToolResponseMessage)
            .flatMap(m -> ((ToolResponseMessage) m).getResponses().stream())
            .map(ToolResponseMessage.ToolResponse::responseData)
            .toList();
    assertThat(toolData).containsExactly("r2");
  }

  @Test
  @DisplayName("工具列表为 Function Calling 格式，且按 Profile.tools 过滤")
  void toolsAttachedInFunctionCallingFormatAndFiltered(@TempDir Path workspace) {
    Session session = newSessionManager().getOrCreate("cli", "alice", "ops-agent");
    session.append(Message.user("hi"));

    Prompt prompt =
        builder(workspace, List.of(toolNamed("http_get"), toolNamed("shell")))
            .build(
                session,
                profileWithBootstrapAndTools(List.of(), List.of("http_get"))); // 只允许 http_get

    ChatOptions options = prompt.getOptions();
    assertThat(options).isInstanceOf(ToolCallingChatOptions.class);
    List<org.springframework.ai.tool.ToolCallback> callbacks =
        ((ToolCallingChatOptions) options).getToolCallbacks();
    assertThat(callbacks).hasSize(1);
    assertThat(callbacks.get(0).getToolDefinition().name()).isEqualTo("http_get");
  }

  @Test
  @DisplayName("工具列表为空：prompt 正常组装，无工具段不报错")
  void emptyToolSetBuildsPromptWithoutToolSection(@TempDir Path workspace) {
    Session session = newSessionManager().getOrCreate("cli", "alice", "ops-agent");
    session.append(Message.user("hi"));

    Prompt prompt =
        builder(workspace, List.of())
            .build(session, profileWithBootstrapAndTools(List.of(), List.of()));

    assertThat(prompt.getOptions()).isNull();
    assertThat(prompt.getInstructions()).hasSize(2); // system + user
  }

  @Test
  @DisplayName("长期记忆段已注入：buildContext 输出在 system 中、位于 Bootstrap 之后日期之前（002 改造点）")
  void memorySectionInjectedViaBuildContext(@TempDir Path workspace) throws Exception {
    Files.writeString(workspace.resolve("AGENTS.md"), "引导");
    Session session = newSessionManager().getOrCreate("cli", "alice", "ops-agent");
    session.append(Message.user("hi"));

    Prompt prompt =
        builder(workspace, List.of())
            .build(session, profileWithBootstrapAndTools(List.of("AGENTS.md"), List.of()));

    String system = prompt.getInstructions().get(0).getText();
    assertThat(system).contains("你是一个专业运维助手").contains("引导").contains("当前日期时间:");
    // 002 改造点：MemoryService.buildContext 输出拼入 system（bootstrap 之后、日期之前）
    assertThat(system).contains("## 长期记忆").contains("记忆上下文: 核心条目测试");
    assertThat(system.indexOf("引导")).isLessThan(system.indexOf("## 长期记忆"));
    assertThat(system.indexOf("## 长期记忆")).isLessThan(system.indexOf("当前日期时间:"));
  }

  private static Message assistantWithToolCall(String callId) {
    List<Message.ToolCallRequest> calls =
        List.of(new Message.ToolCallRequest(callId, "http_get", "{}"));
    return new Message(Message.MessageRole.ASSISTANT, "", calls, List.of());
  }

  private static Message toolResultMessage(String callId, String content) {
    return new Message(
        Message.MessageRole.TOOL,
        content,
        List.of(),
        List.of(new Message.ToolCallResult(callId, content)));
  }
}
