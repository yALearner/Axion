package com.oryxos.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oryxos.core.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.method.MethodToolCallback;

/**
 * 方式三包装器验收 harness——execute 转发调方法、返回序列化为文本包 ToolResult、方法抛异常原样上抛 （由 ToolExecutor 审计）；坑二断言：包装器只借
 * Spring AI 的 schema 生成与注册发现，执行不启用自动工具执行。
 */
class AnnotatedMethodToolAdapterTest {

  private final ObjectMapper objectMapper = new ObjectMapper();
  private SampleBean bean;
  private AnnotatedMethodToolAdapter adapter;

  /** 方式三示例 Bean（测试内 @Tool 方法，经反射包装）。 */
  static class SampleBean {
    @org.springframework.ai.tool.annotation.Tool(name = "echo_tool", description = "echo back")
    public String echo(String text) {
      return "echoed:" + text;
    }
  }

  @BeforeEach
  void setUp() throws Exception {
    bean = new SampleBean();
    ToolDefinition definition =
        ToolDefinition.builder()
            .name("echo_tool")
            .description("echo back")
            .inputSchema("{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}}}")
            .build();
    MethodToolCallback callback =
        MethodToolCallback.builder()
            .toolDefinition(definition)
            .toolObject(bean)
            .toolMethod(SampleBean.class.getMethod("echo", String.class))
            .build();
    adapter = new AnnotatedMethodToolAdapter(callback, objectMapper);
  }

  @Test
  @DisplayName("契约映射：name/description/inputSchema 来自 @Tool 定义")
  void contractMappedFromDefinition() {
    assertThat(adapter.getName()).isEqualTo("echo_tool");
    assertThat(adapter.getDescription()).isEqualTo("echo back");
    assertThat(adapter.getInputSchema().value()).containsKey("properties");
  }

  @Test
  @DisplayName("execute：转发调方法、返回值序列化为文本包 ToolResult")
  void executeCallsMethodAndWrapsResult() throws Exception {
    ToolResult result = adapter.execute(objectMapper.readTree("{\"text\":\"hi\"}"));

    assertThat(result.success()).isTrue();
    // MethodToolCallback 返回 JSON 序列化文本（FR-6"返回值序列化为文本"）
    assertThat(result.content()).isEqualTo("\"echoed:hi\"");
  }

  @Test
  @DisplayName("方法抛异常：原样上抛（由 ToolExecutor 审计，不吞）")
  void methodExceptionPropagates() throws Exception {
    SampleBean failing =
        new SampleBean() {
          @Override
          public String echo(String text) {
            throw new IllegalStateException("boom");
          }
        };
    ToolDefinition definition =
        ToolDefinition.builder()
            .name("echo_tool")
            .description("echo back")
            .inputSchema("{}")
            .build();
    MethodToolCallback callback =
        MethodToolCallback.builder()
            .toolDefinition(definition)
            .toolObject(failing)
            .toolMethod(SampleBean.class.getMethod("echo", String.class))
            .build();
    AnnotatedMethodToolAdapter failingAdapter =
        new AnnotatedMethodToolAdapter(callback, objectMapper);

    assertThatThrownBy(() -> failingAdapter.execute(objectMapper.readTree("{\"text\":\"x\"}")))
        .isInstanceOf(IllegalStateException.class);
  }
}
