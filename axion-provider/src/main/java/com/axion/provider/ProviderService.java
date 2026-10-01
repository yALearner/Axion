package com.oryxos.provider;

import com.oryxos.core.LlmGateway;
import com.oryxos.core.Profile;
import com.oryxos.storage.LlmCall;
import com.oryxos.storage.LlmCallRepository;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

/**
 * LLM 调用统一门面（核心能力一）。
 *
 * <p>对外只露一个方法 {@link #chat(String, Profile, Prompt)}：按 Profile 的 provider name 从显式映射表取 {@link
 * ChatModel} 完成一次同步阻塞调用。职责边界划窄—— 只做三件事：挑对模型、发起一次调用、把结果拿回来；循环怎么转、工具怎么执行、 上下文怎么拼装都不归它管。
 *
 * <p>两条硬约束（有回归测试钉死）：
 *
 * <ul>
 *   <li>显式映射：provider name → ChatModel，禁止扫描容器里的 ChatModel 集合区分 Provider；
 *   <li>自动执行关闭：无论调用方怎么传 options，内部工具自动执行一律关闭 （工具调用请求只透传，由后续节的 ToolExecutor 执行）。
 * </ul>
 *
 * <p>审计（day one）：成功与失败都落 {@code llm_calls}；失败路径先落账再把异常原样上抛。
 */
public final class ProviderService implements LlmGateway {

  private static final Logger LOG = LoggerFactory.getLogger(ProviderService.class);

  /** 显式映射：provider name → ChatModel。 */
  private final Map<String, ChatModel> providerMap;

  private final LlmCallRepository llmCallRepository;

  public ProviderService(Map<String, ChatModel> providerMap, LlmCallRepository llmCallRepository) {
    this.providerMap = Map.copyOf(providerMap);
    this.llmCallRepository = llmCallRepository;
  }

  /**
   * 一次同步阻塞调用。
   *
   * @param sessionId 发起调用的会话标识（审计按此关联；会话生命周期不归本模块管）
   * @param profile Agent 运行时配置（路由与默认调用参数的来源）
   * @param prompt 要发送的内容（消息列表 + 可用工具）
   */
  @Override
  @SuppressFBWarnings(
      value = "THROWS_METHOD_THROWS_RUNTIMEEXCEPTION",
      justification = "契约要求：失败路径先落审计、异常原样上抛（需求文档 FR-5/FR-7、contracts/provider-service.md 行为不变量）")
  public ChatResponse chat(String sessionId, Profile profile, Prompt prompt) {
    String providerName = profile.provider().name();
    ChatModel model = providerMap.get(providerName);
    if (model == null) {
      throw new ProviderNotFoundException(providerName);
    }
    Prompt effective = withToolExecutionDisabled(prompt, profile);
    long startedAt = System.currentTimeMillis();
    try {
      ChatResponse response = model.call(effective);
      auditSuccess(sessionId, profile, response, System.currentTimeMillis() - startedAt);
      return response;
    } catch (RuntimeException e) {
      auditFailure(sessionId, profile, e, System.currentTimeMillis() - startedAt);
      throw e;
    }
  }

  /**
   * 坑二防线：内部工具自动执行一律关闭；Profile 的 model/temperature 在请求未指定时补上。
   * 调用方挂上的工具回调（toolCallbacks/toolNames/toolContext）原样保留——只翻译、不执行。
   */
  private Prompt withToolExecutionDisabled(Prompt prompt, Profile profile) {
    ChatOptions options = prompt.getOptions();
    ToolCallingChatOptions.Builder builder = ToolCallingChatOptions.builder();
    if (options instanceof ToolCallingChatOptions toolOptions) {
      builder.toolCallbacks(toolOptions.getToolCallbacks());
      builder.toolNames(toolOptions.getToolNames());
      builder.toolContext(toolOptions.getToolContext());
    }
    if (profile.provider().model() != null) {
      builder.model(profile.provider().model());
    }
    if (profile.provider().temperature() != null) {
      builder.temperature(profile.provider().temperature());
    }
    builder.internalToolExecutionEnabled(false);
    return new Prompt(prompt.getInstructions(), builder.build());
  }

  /** 成功路径落账；审计本身失败只记日志，不影响已成功的调用结果。 */
  private void auditSuccess(
      String sessionId, Profile profile, ChatResponse response, long durationMs) {
    try {
      Usage usage = usageOf(response);
      llmCallRepository.save(
          new LlmCall(
              sessionId,
              profile.provider().name(),
              profile.provider().model(),
              usage == null ? null : usage.getPromptTokens(),
              usage == null ? null : usage.getCompletionTokens(),
              usage == null ? null : usage.getTotalTokens(),
              true,
              null,
              durationMs,
              Instant.now()));
    } catch (RuntimeException auditError) {
      // 会话/provider 细节不入日志参数（防 CRLF 注入）；落库失败的根因在异常堆栈里
      LOG.error("llm_calls 审计落库失败（调用本身已成功）", auditError);
    }
  }

  /** 失败路径：先落账（success=false + 原因），异常继续上抛；审计失败不得覆盖原异常。 */
  private void auditFailure(
      String sessionId, Profile profile, RuntimeException error, long durationMs) {
    try {
      llmCallRepository.save(
          new LlmCall(
              sessionId,
              profile.provider().name(),
              profile.provider().model(),
              null,
              null,
              null,
              false,
              error.getMessage(),
              durationMs,
              Instant.now()));
    } catch (RuntimeException auditError) {
      // 会话/provider 细节不入日志参数（防 CRLF 注入）；落库失败的根因在异常堆栈里
      LOG.error("llm_calls 失败审计落库失败", auditError);
    }
  }

  private Usage usageOf(ChatResponse response) {
    if (response == null) {
      return null;
    }
    ChatResponseMetadata metadata = response.getMetadata();
    return metadata.getUsage();
  }
}
