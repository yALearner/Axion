package com.oryxos.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.oryxos.core.JsonSchema;
import com.oryxos.core.OryxTool;
import com.oryxos.core.ToolResult;
import com.oryxos.tool.ActionType;
import com.oryxos.tool.Sandbox;
import com.oryxos.tool.SandboxAction;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 内置 Tool {@code read_file}（FR-2）：读取文件内容——execute 首行 {@code sandbox.enforce(FILE_READ, path)} 先于
 * IO（坑十延续：顺序反了就是漏洞），通过才真正读；路径以参数传入、不硬编码。不存在/不可读 → 明确报错 （ToolResult.failure，不静默）。路径白名单规则本体归 23/24 节。
 *
 * <p>纯类交付无组件注解（G4-C1），装配处显式 {@code @Bean}；审计复用 ToolExecutor 既有路径。
 */
public class ReadFileTool implements OryxTool {

  private final Sandbox sandbox;

  public ReadFileTool(Sandbox sandbox) {
    this.sandbox = sandbox;
  }

  @Override
  public String getName() {
    return "read_file";
  }

  @Override
  public String getDescription() {
    return "读取指定路径的文件内容（文本）。path 为文件路径（必填）；文件不存在或不可读时明确报错。";
  }

  @Override
  public JsonSchema getInputSchema() {
    return new JsonSchema(
        Map.of(
            "type",
            "object",
            "properties",
            Map.of("path", Map.of("type", "string", "description", "要读取的文件路径")),
            "required",
            List.of("path")));
  }

  @Override
  public ToolResult execute(JsonNode input) {
    Path path = Path.of(input.get("path").asText());
    sandbox.enforce(new SandboxAction(ActionType.FILE_READ, path.toString())); // 坑十：enforce 先于 IO
    try {
      return ToolResult.success(Files.readString(path, StandardCharsets.UTF_8));
    } catch (IOException e) {
      return ToolResult.failure("读取文件失败: " + path + "（" + e.getMessage() + "）", false);
    }
  }
}
