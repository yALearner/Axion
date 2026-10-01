package com.oryxos.core;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.function.FunctionToolCallback;

/**
 * 每轮 Prompt 组装器。四部分按序（技术方案 §4.2）：
 *
 * <ol>
 *   <li>system：角色设定（{@code Profile.identity.prompt}）+ Bootstrap 与 Skill 元数据（{@link ContextLoader}
 *       每轮现读）+ <strong>记忆注入</strong>（{@link MemoryService#buildContext}：长期记忆 + 会话历史，每次重新读不缓存） +
 *       **末尾附当前日期时间**（LLM 自己不知道今天几号，定时场景的"今天"全靠这一行）
 *   <li>对话历史：只留最近 {@code maxHistoryTurns} 轮（默认 20），超出截断（坑二：TOOL 消息跟随所属 ASSISTANT 响应成组保留，不切断一轮内的
 *       tool 调用链）
 *   <li>可用工具列表：按 {@code Profile.tools} 过滤注入的工具集 → {@link ToolSchemaAdapter} 翻译 → Function Calling
 *       格式（ToolCallbacks 挂上 options；ProviderService 侧自动执行已关闭）
 * </ol>
 */
public final class PromptBuilder {

  private static final Logger LOG = LoggerFactory.getLogger(PromptBuilder.class);

  private final ContextLoader contextLoader;
  private final ToolSchemaAdapter toolSchemaAdapter;
  private final Map<String, OryxTool> tools;
  private final MemoryService memoryService;

  /**
   * @param tools 注入的完整工具集（第 20 节起由 ToolRegistry 提供，契约不变）
   * @param memoryService 记忆统一门面（002 改造点：buildContext 输出拼入 system prompt，技术方案 §5.3 明文集成点）
   */
  public PromptBuilder(
      ContextLoader contextLoader,
      ToolSchemaAdapter toolSchemaAdapter,
      Map<String, OryxTool> tools,
      MemoryService memoryService) {
    this.contextLoader = contextLoader;
    this.toolSchemaAdapter = toolSchemaAdapter;
    this.tools = Map.copyOf(tools);
    this.memoryService = memoryService;
  }

  /** 组装一轮 Prompt。 */
  public Prompt build(Session session, Profile profile) {
    List<Message> instructions = new ArrayList<>();
    // ① system：角色设定 + ContextLoader 产物 + MemoryService 记忆注入 + 当前日期时间（末尾）
    String system =
        String.join(System.lineSeparator(), identity(profile), contextLoader.load(profile))
            + memoryService.buildContext(session) // ② 记忆注入：长期记忆 + 会话历史（每次重新读，坑十五联动）
            + System.lineSeparator()
            + "当前日期时间: "
            + LocalDateTime.now(ZoneId.systemDefault());
    instructions.add(new SystemMessage(system));
    // ③ 会话历史：最近 N 轮（坑二截断语义）
    for (com.oryxos.core.Message m : historyOf(session, profile.settings().maxHistoryTurns())) {
      instructions.add(toSpringMessage(m));
    }
    // ④ 可用工具列表：按 Profile.tools 过滤 → Function Calling 格式
    List<ToolDefinition> definitions = toolSchemaAdapter.toToolDefinitions(selectTools(profile));
    if (definitions.isEmpty()) {
      return new Prompt(instructions);
    }
    ToolCallback[] callbacks = toToolCallbacks(definitions);
    ChatOptions options =
        ToolCallingChatOptions.builder().toolCallbacks(List.of(callbacks)).build();
    return new Prompt(instructions, options);
  }

  /** 纯数据定义 → ToolCallback：回调体永不执行（执行权唯一在 ToolExecutor——若框架误调立即炸响，坑六防线）。 */
  private ToolCallback[] toToolCallbacks(List<ToolDefinition> definitions) {
    java.util.function.Function<String, String> neverCalled =
        s -> {
          throw new UnsupportedOperationException("工具执行只经 ToolExecutor（执行权唯一，宪法 II）");
        };
    return definitions.stream()
        .map(
            d ->
                FunctionToolCallback.builder(d.name(), neverCalled)
                    .description(d.description())
                    .inputSchema(d.inputSchema())
                    .inputType(String.class)
                    .build())
        .toArray(ToolCallback[]::new);
  }

  private String identity(Profile profile) {
    return profile.identity().prompt() == null ? "" : profile.identity().prompt();
  }

  /** 按 Profile.tools 名字过滤注入的工具集；未知工具名记 WARN 跳过（注册校验归第 20 节 ToolRegistry）。 */
  private List<OryxTool> selectTools(Profile profile) {
    List<String> allowed = profile.tools();
    if (allowed == null || allowed.isEmpty()) {
      return List.of();
    }
    List<OryxTool> selected = new ArrayList<>();
    for (String name : allowed) {
      OryxTool tool = tools.get(name);
      if (tool == null) {
        // profile/工具名为用户可控值，不入日志参数（防 CRLF 注入）
        LOG.warn("Profile 引用的工具未在注入的工具集中");
      } else {
        selected.add(tool);
      }
    }
    return selected;
  }

  /**
   * 坑二截断语义：从尾部回数，保留最近 N 轮；一轮 = 一条 USER 消息 + 随后的 ASSISTANT 响应 + 其全部 TOOL 结果——TOOL
   * 消息跟随所属轮成组保留，绝不把一轮内的 tool 调用链拦腰切断。
   */
  private List<com.oryxos.core.Message> historyOf(Session session, int maxTurns) {
    List<com.oryxos.core.Message> all = session.messages();
    int keptTurns = 0;
    for (int i = all.size() - 1; i >= 0; i--) {
      if (all.get(i).role() == com.oryxos.core.Message.MessageRole.USER) {
        keptTurns++;
        if (keptTurns == maxTurns) {
          return all.subList(i, all.size());
        }
      }
    }
    return all; // 不足 N 轮：全部保留
  }

  /** core Message → Spring AI 消息（Session 历史保持框架无关，只在组装边界转换）。 */
  private Message toSpringMessage(com.oryxos.core.Message m) {
    return switch (m.role()) {
      case USER -> new UserMessage(text(m.content()));
      case ASSISTANT -> {
        if (m.toolCalls().isEmpty()) {
          yield new AssistantMessage(text(m.content()));
        }
        List<AssistantMessage.ToolCall> calls =
            m.toolCalls().stream()
                .map(
                    c -> new AssistantMessage.ToolCall(c.id(), "function", c.name(), c.arguments()))
                .toList();
        yield AssistantMessage.builder().content(text(m.content())).toolCalls(calls).build();
      }
      case TOOL -> {
        // core 契约只存 toolCallId + 内容（001 已定）；回填 name 以空串占位，模型侧按 id 对位
        List<ToolResponseMessage.ToolResponse> responses =
            m.toolResults().stream()
                .map(
                    r ->
                        new ToolResponseMessage.ToolResponse(r.toolCallId(), "", text(r.content())))
                .toList();
        yield ToolResponseMessage.builder().responses(responses).build();
      }
      case SYSTEM -> new SystemMessage(text(m.content()));
    };
  }

  private String text(String value) {
    return value == null ? "" : value;
  }
}
