package com.oryxos.tool.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oryxos.core.ToolResult;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * McpToolAdapter 验收 harness——getName/getDescription/getInputSchema 映射 tools/list 返回； execute
 * 参数原样转发、结果包 ToolResult（成功 success / 失败 retryable=true）。
 */
class McpToolAdapterTest {

  private final ObjectMapper objectMapper = new ObjectMapper();
  private McpSyncClient client;
  private McpToolAdapter adapter;

  @BeforeEach
  void setUp() {
    client = mock(McpSyncClient.class);
    McpSchema.Tool spec =
        new McpSchema.Tool(
            "mcp_echo",
            null,
            "echo back",
            new McpSchema.JsonSchema(
                "object",
                Map.of("text", Map.of("type", "string")),
                List.of("text"),
                false,
                Map.of(),
                Map.of()),
            null,
            null,
            null);
    adapter = new McpToolAdapter(client, spec, objectMapper);
  }

  @Test
  @DisplayName("契约映射：name/description/inputSchema 来自 tools/list 返回")
  void contractMappedFromSpec() {
    assertThat(adapter.getName()).isEqualTo("mcp_echo");
    assertThat(adapter.getDescription()).isEqualTo("echo back");
    assertThat(adapter.getInputSchema().value()).containsKey("properties");
  }

  @Test
  @DisplayName("execute：参数原样 JSON-RPC 转发、成功结果包 ToolResult.success")
  void executeForwardsAndWrapsSuccess() {
    when(client.callTool(any(McpSchema.CallToolRequest.class)))
        .thenReturn(
            new McpSchema.CallToolResult.Builder()
                .content(List.of(new McpSchema.TextContent("echoed")))
                .isError(false)
                .build());

    ToolResult result = adapter.execute(objectMapper.createObjectNode().put("text", "hello"));

    assertThat(result.success()).isTrue();
    assertThat(result.content()).contains("echoed");
    verify(client).callTool(any(McpSchema.CallToolRequest.class));
  }

  @Test
  @DisplayName("execute：MCP 失败（isError）→ failure + retryable=true")
  void executeFailureRetryable() {
    when(client.callTool(any(McpSchema.CallToolRequest.class)))
        .thenReturn(
            new McpSchema.CallToolResult.Builder()
                .content(List.of(new McpSchema.TextContent("boom")))
                .isError(true)
                .build());

    ToolResult result = adapter.execute(objectMapper.createObjectNode().put("text", "hello"));

    assertThat(result.success()).isFalse();
    assertThat(result.retryable()).isTrue();
    assertThat(result.errorMessage()).contains("boom");
  }
}
