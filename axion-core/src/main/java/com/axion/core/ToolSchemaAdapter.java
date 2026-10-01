package com.oryxos.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * Function Calling 适配：把 OryxOS 内部的 {@link OryxTool} 抽象翻译成 Spring AI 的工具定义。
 *
 * <p>只做格式转换、不执行——工具的实际调度与执行由 {@link ToolExecutor} 控制；产物是纯数据定义（{@link ToolDefinition}），不含任何执行逻辑。
 *
 * <p>002-react G2 拍板自 oryxos-provider 迁入本模块（PromptBuilder 在 core 消费它，依赖方向 core ← provider
 * 下无法反向引用）；逻辑零改动。
 */
public final class ToolSchemaAdapter {

  private final ObjectMapper objectMapper;

  public ToolSchemaAdapter(ObjectMapper objectMapper) {
    // 防御性拷贝：不持有外部可变对象
    this.objectMapper = objectMapper.copy();
  }

  /** 只翻译：name/description 原样传递，inputSchema 序列化为 JSON 字符串。 */
  public List<ToolDefinition> toToolDefinitions(List<OryxTool> tools) {
    List<ToolDefinition> result = new ArrayList<>();
    for (OryxTool tool : tools) {
      result.add(
          ToolDefinition.builder()
              .name(tool.getName())
              .description(tool.getDescription())
              .inputSchema(toJson(tool))
              .build());
    }
    return result;
  }

  private String toJson(OryxTool tool) {
    try {
      return objectMapper.writeValueAsString(tool.getInputSchema().value());
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("工具 [" + tool.getName() + "] 的 schema 无法序列化为 JSON", e);
    }
  }
}
