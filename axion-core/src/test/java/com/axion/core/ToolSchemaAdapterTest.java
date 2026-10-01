package com.oryxos.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

/** ToolSchemaAdapter 验收 harness——schema 字段一一对齐、产物不含执行逻辑（002 自 oryxos-provider 随迁，逻辑零改动）。 */
class ToolSchemaAdapterTest {

  private final ToolSchemaAdapter adapter = new ToolSchemaAdapter(new ObjectMapper());

  @Test
  @DisplayName("schema 字段一一对齐")
  void translatesSchemaFields() throws Exception {
    OryxTool tool = mock(OryxTool.class);
    when(tool.getName()).thenReturn("http_get");
    when(tool.getDescription()).thenReturn("发起 HTTP GET 请求");
    when(tool.getInputSchema())
        .thenReturn(
            new JsonSchema(
                Map.of("type", "object", "properties", Map.of("url", Map.of("type", "string")))));

    List<ToolDefinition> definitions = adapter.toToolDefinitions(List.of(tool));

    assertThat(definitions).hasSize(1);
    ToolDefinition definition = definitions.get(0);
    assertThat(definition.name()).isEqualTo("http_get");
    assertThat(definition.description()).isEqualTo("发起 HTTP GET 请求");
    Map<String, Object> parsed = new ObjectMapper().readValue(definition.inputSchema(), Map.class);
    assertThat(parsed).containsEntry("type", "object");
    assertThat(parsed).containsKey("properties");
  }

  @Test
  @DisplayName("产物不含执行逻辑：翻译结果是纯数据定义")
  void noExecutionLogicInOutput() {
    OryxTool tool = mock(OryxTool.class);
    when(tool.getName()).thenReturn("t");
    when(tool.getDescription()).thenReturn("d");
    when(tool.getInputSchema()).thenReturn(new JsonSchema(Map.of()));

    List<ToolDefinition> definitions = adapter.toToolDefinitions(List.of(tool));

    // ToolDefinition 是纯数据接口（name/description/inputSchema），无任何执行入口
    assertThat(definitions.get(0)).isInstanceOf(DefaultToolDefinition.class);
  }
}
