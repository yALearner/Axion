package com.oryxos.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.oryxos.core.JsonSchema;
import com.oryxos.core.MemoryScope;
import com.oryxos.core.MemoryService;
import com.oryxos.core.OryxTool;
import com.oryxos.core.ToolResult;
import java.util.List;
import java.util.Map;

/**
 * 内置 Tool {@code save_memory}（FR-7）：把一条长期记忆写入指定分区。scope 可选（core/archival，缺省 archival——坑十七：写哪区由
 * Agent 显式判断、工具层不猜）；非法值明确报错；成功返回"已记住"。
 *
 * <p>只依赖 {@link MemoryService} 接口（接口墙）；implements OryxTool 纯实现（005 机械适配：手写 JsonSchema、
 * 无组件注解）；写入失败异常上抛由 ToolExecutor 审计 success=false（不静默"已记住"）。
 */
public class SaveMemoryTool implements OryxTool {

  private final MemoryService memoryService;

  public SaveMemoryTool(MemoryService memoryService) {
    this.memoryService = memoryService;
  }

  @Override
  public String getName() {
    return "save_memory";
  }

  @Override
  public String getDescription() {
    return "保存一条长期记忆（跨对话记住用户偏好、项目背景等）。content 为要记住的内容（必填）；"
        + "scope 可选：core（核心记忆，每次对话都完整注入）/archival（归档记忆，按需检索），缺省 archival。";
  }

  @Override
  public JsonSchema getInputSchema() {
    return new JsonSchema(
        Map.of(
            "type",
            "object",
            "properties",
            Map.of(
                "content",
                Map.of("type", "string", "description", "要记住的内容"),
                "scope",
                Map.of(
                    "type",
                    "string",
                    "enum",
                    List.of("core", "archival"),
                    "description",
                    "写入分区：core=核心记忆 / archival=归档记忆，缺省 archival")),
            "required",
            List.of("content")));
  }

  @Override
  public ToolResult execute(JsonNode input) {
    JsonNode contentNode = input.get("content");
    if (contentNode == null || contentNode.asText().isBlank()) {
      return ToolResult.failure("content 必填", false);
    }
    String scopeRaw = input.path("scope").asText("");
    MemoryScope scope =
        switch (scopeRaw) {
          case "", "archival" -> MemoryScope.ARCHIVAL; // 坑十七：缺省 archival
          case "core" -> MemoryScope.CORE;
          default -> null; // 非法值：参数校验错误走 ToolResult.failure（重试救不了非法参数，不标 retryable）
        };
    if (scope == null) {
      return ToolResult.failure("scope 非法值: " + scopeRaw + "（取值 core/archival）", false);
    }
    memoryService.remember(contentNode.asText(), scope); // 失败异常上抛 → ToolExecutor 审计
    return ToolResult.success("已记住");
  }
}
