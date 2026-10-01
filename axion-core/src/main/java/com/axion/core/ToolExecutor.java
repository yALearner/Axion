package com.oryxos.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oryxos.storage.ToolInvocation;
import com.oryxos.storage.ToolInvocationRepository;
import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;

/**
 * 工具执行点——全系统唯一的工具调度与执行入口（执行权唯一，宪法 II：不能有第二条路）。
 *
 * <p>按名从注入的工具集找到 {@link OryxTool} → 执行 → 结果包装成 {@link ToolResult} → **成功与失败都写 {@code
 * tool_invocations}**（宪法 V）。失败不吞：错误原因进审计与结果、带 retryable 提示回传——重试是 LLM 下一轮 的决定，本类不内部自动重试。
 *
 * <p>**不持 Sandbox 引用**：涉外 IO 的 enforce 由各工具在 execute 首行自执行（contracts/sandbox.md）——core 不反向 依赖
 * oryxos-tool。工具集以 {@code Map<String, OryxTool>} 注入，第 20 节换成 ToolRegistry 不改本类。
 */
public final class ToolExecutor {

  private static final Logger LOG = LoggerFactory.getLogger(ToolExecutor.class);

  private final Map<String, OryxTool> tools;
  private final ToolInvocationRepository audit;
  private final ObjectMapper objectMapper;

  public ToolExecutor(
      Map<String, OryxTool> tools, ToolInvocationRepository audit, ObjectMapper objectMapper) {
    this.tools = Map.copyOf(tools);
    this.audit = audit;
    this.objectMapper = objectMapper.copy();
  }

  /** 执行一次模型请求的工具调用；任何路径都不上抛（错误以失败结果回传，异常不吞、进审计与结果）。 */
  public ToolResult execute(String sessionId, AssistantMessage.ToolCall call) {
    String toolName = call.name();
    String inputJson = call.arguments();
    long startedAt = System.currentTimeMillis();
    OryxTool tool = tools.get(toolName);
    if (tool == null) {
      String message = "工具不存在: " + toolName;
      auditAndLog(
          sessionId,
          toolName,
          inputJson,
          null,
          false,
          message,
          System.currentTimeMillis() - startedAt);
      return ToolResult.failure(message, false);
    }
    try {
      JsonNode input = parseArguments(inputJson);
      ToolResult result = tool.execute(input);
      long durationMs = System.currentTimeMillis() - startedAt;
      if (result.success()) {
        auditAndLog(sessionId, toolName, inputJson, result.content(), true, null, durationMs);
      } else {
        auditAndLog(sessionId, toolName, inputJson, null, false, result.errorMessage(), durationMs);
      }
      return result;
    } catch (RuntimeException e) {
      long durationMs = System.currentTimeMillis() - startedAt;
      String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
      auditAndLog(sessionId, toolName, inputJson, null, false, message, durationMs);
      // 异常不吞：原因进审计与结果；retryable 提示为 true，重试与否由 LLM 下一轮判断
      return ToolResult.failure(message, true);
    }
  }

  private JsonNode parseArguments(String inputJson) {
    if (inputJson == null || inputJson.isBlank()) {
      return objectMapper.createObjectNode();
    }
    try {
      return objectMapper.readTree(inputJson);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("工具参数不是合法 JSON", e);
    }
  }

  /** 审计落账 + 结构化日志；审计本身失败只记日志，不影响工具执行结果。敏感参数（input/result）不入日志。 */
  private void auditAndLog(
      String sessionId,
      String toolName,
      String inputJson,
      String resultJson,
      boolean success,
      String errorMessage,
      long durationMs) {
    try {
      audit.save(
          new ToolInvocation(
              sessionId,
              toolName,
              inputJson,
              resultJson,
              success,
              errorMessage,
              durationMs,
              Instant.now()));
    } catch (RuntimeException auditError) {
      LOG.error("tool_invocations 审计落库失败（工具执行本身不受影响）", auditError);
    }
    // sessionId/toolName 为用户可控值，不入日志参数（防 CRLF 注入）；关联审计在 tool_invocations 表内
    LOG.info("工具执行完成: success={} durationMs={}", success, durationMs);
  }
}
