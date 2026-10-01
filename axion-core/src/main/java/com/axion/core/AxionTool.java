package com.oryxos.core;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * OryxTool 接口——所有 Tool 的统一抽象（内置 Tool 与 MCP Tool 都汇入此接口）。
 *
 * <p>本节只交付抽象与 schema 生成；内置实现归 US-4（核心能力四，oryxos-tool 模块）。
 */
public interface OryxTool {

  /** 工具名（Function Calling 中模型可见的唯一标识）。 */
  String getName();

  /** 用途说明（注入模型，指导何时使用该工具）。 */
  String getDescription();

  /** 参数说明（JSON Schema，经适配层翻译为模型可理解的工具格式）。 */
  JsonSchema getInputSchema();

  /** 执行工具；只由 ToolExecutor 调度，Provider 层只翻译、不执行。 */
  ToolResult execute(JsonNode input);
}
