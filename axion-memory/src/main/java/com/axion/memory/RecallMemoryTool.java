package com.axion.memory;

import com.axion.core.AxionTool;
import com.axion.core.JsonSchema;
import com.axion.core.MemoryService;
import com.axion.core.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;

/**
 * 内置 Tool {@code recall_memory}（FR-7）：按关键词检索长期记忆归档区（坑十八：只搜归档）。未命中返回 "没有找到相关记忆"（不抛异常）；命中换行拼接返回。
 *
 * <p>只依赖 {@link MemoryService} 接口（接口墙）；implements AxionTool 纯实现（005 机械适配：手写 JsonSchema、
 * 无组件注解）；后端故障异常上抛由 ToolExecutor 审计 success=false。
 */
public class RecallMemoryTool implements AxionTool {

  private final MemoryService memoryService;

  public RecallMemoryTool(MemoryService memoryService) {
    this.memoryService = memoryService;
  }

  @Override
  public String getName() {
    return "recall_memory";
  }

  @Override
  public String getDescription() {
    return "按关键词检索长期记忆的归档区。keyword 为检索关键词（必填）；" + "未命中返回「没有找到相关记忆」，命中返回匹配条目。";
  }

  @Override
  public JsonSchema getInputSchema() {
    return new JsonSchema(
        Map.of(
            "type",
            "object",
            "properties",
            Map.of("keyword", Map.of("type", "string", "description", "检索关键词")),
            "required",
            List.of("keyword")));
  }

  @Override
  public ToolResult execute(JsonNode input) {
    JsonNode keywordNode = input.get("keyword");
    if (keywordNode == null || keywordNode.asText().isBlank()) {
      return ToolResult.failure("keyword 必填", false);
    }
    List<String> hits = memoryService.recall(keywordNode.asText()); // 失败异常上抛 → ToolExecutor 审计
    if (hits.isEmpty()) {
      return ToolResult.success("没有找到相关记忆");
    }
    return ToolResult.success(String.join("\n", hits));
  }
}
