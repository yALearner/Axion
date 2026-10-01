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
 * 内置 Tool {@code write_file}（FR-2）：写入文件内容——execute 首行 {@code sandbox.enforce(FILE_WRITE, path)} 先于
 * IO（坑十）；覆盖已存在文件；父目录不存在 → 明确报错、不递归建目录（参数规格表）。路径白名单规则本体归 23/24 节。纯类交付无组件注解（G4-C1）。
 */
public class WriteFileTool implements OryxTool {

  private final Sandbox sandbox;

  public WriteFileTool(Sandbox sandbox) {
    this.sandbox = sandbox;
  }

  @Override
  public String getName() {
    return "write_file";
  }

  @Override
  public String getDescription() {
    return "把文本内容写入指定路径的文件（覆盖已存在文件）。path 为文件路径（必填）、content 为内容（必填）；" + "父目录不存在时明确报错，不会自动创建目录。";
  }

  @Override
  public JsonSchema getInputSchema() {
    return new JsonSchema(
        Map.of(
            "type",
            "object",
            "properties",
            Map.of(
                "path", Map.of("type", "string", "description", "要写入的文件路径"),
                "content", Map.of("type", "string", "description", "要写入的文本内容")),
            "required",
            List.of("path", "content")));
  }

  @Override
  public ToolResult execute(JsonNode input) {
    Path path = Path.of(input.get("path").asText());
    String content = input.get("content").asText();
    sandbox.enforce(new SandboxAction(ActionType.FILE_WRITE, path.toString())); // 坑十：enforce 先于 IO
    Path parent = path.getParent();
    if (parent != null && !Files.isDirectory(parent)) {
      return ToolResult.failure("父目录不存在: " + parent + "（不自动创建目录）", false);
    }
    try {
      byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
      Files.write(path, bytes);
      return ToolResult.success("已写入 " + bytes.length + " 字节");
    } catch (IOException e) {
      return ToolResult.failure("写入文件失败: " + path + "（" + e.getMessage() + "）", false);
    }
  }
}
