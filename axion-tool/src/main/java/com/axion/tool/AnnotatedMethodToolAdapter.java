package com.oryxos.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oryxos.core.JsonSchema;
import com.oryxos.core.OryxTool;
import com.oryxos.core.ToolResult;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Map;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.ai.tool.method.MethodToolCallback;

/**
 * 方式三包装器（FR-6）：把业务方 {@code @Tool} 注解方法包装成 {@link OryxTool} 注册进 ToolRegistry—— 仅借 Spring AI 做 schema
 * 生成与注册发现（宪法 II 允许的两件事）；**执行不启用 Spring AI 自动执行** （坑二：自动执行 = tool 被调两次）——包装器 execute
 * 内直接调方法、返回序列化为文本包 {@link ToolResult}、 方法抛异常原样上抛由 ToolExecutor 审计（坑十一口径）。
 *
 * <p>扫描接线在装配处（CliAgentConfiguration）经 {@code MethodToolCallbackProvider} 逐个包装注册； Provider bean
 * 不可用（Spring AI 自动配置未生效）时按 FR-6 降级路径处理：手动注册 + flow-status 记录。
 */
public class AnnotatedMethodToolAdapter implements OryxTool {

  private final MethodToolCallback callback;
  private final ObjectMapper objectMapper;

  public AnnotatedMethodToolAdapter(MethodToolCallback callback, ObjectMapper objectMapper) {
    this.callback = callback;
    this.objectMapper = objectMapper.copy();
  }

  @Override
  public String getName() {
    return callback.getToolDefinition().name();
  }

  @Override
  public String getDescription() {
    ToolDefinition definition = callback.getToolDefinition();
    return definition.description();
  }

  @Override
  public JsonSchema getInputSchema() {
    String json = callback.getToolDefinition().inputSchema();
    try {
      @SuppressWarnings("unchecked")
      Map<String, Object> value = objectMapper.readValue(json, Map.class);
      return new JsonSchema(value);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("工具 [" + getName() + "] 的 schema 无法解析", e);
    }
  }

  @Override
  @SuppressFBWarnings(
      value = "THROWS_METHOD_THROWS_RUNTIMEEXCEPTION",
      justification = "契约要求：方法异常原样上抛由 ToolExecutor 审计（需求文档 FR-6，001-provider 同款先例）")
  public ToolResult execute(JsonNode input) {
    try {
      String result = callback.call(input.toString()); // 返回 JSON 序列化文本
      return ToolResult.success(result);
    } catch (ToolExecutionException e) {
      // Spring AI 把方法异常包成 ToolExecutionException——还原"原样上抛"，由 ToolExecutor 审计
      if (e.getCause() instanceof RuntimeException runtime) {
        throw runtime;
      }
      throw e;
    }
  }
}
