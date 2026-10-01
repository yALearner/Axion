package com.oryxos.tool.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oryxos.core.JsonSchema;
import com.oryxos.core.OryxTool;
import com.oryxos.core.ToolResult;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * MCP 工具适配器（FR-5）：把 MCP server 暴露的工具包装成 {@link OryxTool}——getName/getDescription/getInputSchema 映射
 * {@code tools/list} 返回；execute 经 JSON-RPC（sync 门面）原样转发参数、结果包 {@link ToolResult} （失败 {@code
 * retryable=true}，重试与否由 LLM 下一轮判断）。纯类交付无组件注解（G4-C1）。
 */
public class McpToolAdapter implements OryxTool {

  private final McpSyncClient client;
  private final McpSchema.Tool spec;
  private final ObjectMapper objectMapper;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "McpSyncClient 为连接管理服务持有的同步协议栈（不可复制），仅本类只读使用、不暴露引用（004 WebhookNotifyAdapter 同款先例）")
  public McpToolAdapter(McpSyncClient client, McpSchema.Tool spec, ObjectMapper objectMapper) {
    this.client = client;
    this.spec = spec;
    this.objectMapper = objectMapper.copy();
  }

  @Override
  public String getName() {
    return spec.name();
  }

  @Override
  public String getDescription() {
    return spec.description() == null ? "" : spec.description();
  }

  @Override
  public JsonSchema getInputSchema() {
    McpSchema.JsonSchema schema = spec.inputSchema();
    Map<String, Object> value = new java.util.LinkedHashMap<>();
    if (schema.properties() != null) {
      value.put("properties", Map.copyOf(schema.properties()));
    }
    if (schema.required() != null) {
      value.put("required", schema.required());
    }
    if (schema.type() != null) {
      value.put("type", schema.type());
    }
    return new JsonSchema(value);
  }

  @Override
  public ToolResult execute(JsonNode input) {
    Map<String, Object> arguments =
        objectMapper.convertValue(input, new TypeReference<Map<String, Object>>() {});
    McpSchema.CallToolRequest request =
        McpSchema.CallToolRequest.builder().name(spec.name()).arguments(arguments).build();
    McpSchema.CallToolResult result = client.callTool(request);
    String text =
        result.content() == null
            ? ""
            : result.content().stream()
                .filter(c -> c instanceof McpSchema.TextContent)
                .map(c -> ((McpSchema.TextContent) c).text())
                .collect(Collectors.joining("\n"));
    if (Boolean.TRUE.equals(result.isError())) {
      return ToolResult.failure(text, true); // MCP 失败可重试
    }
    return ToolResult.success(text);
  }
}
