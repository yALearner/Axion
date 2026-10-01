package com.oryxos.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.oryxos.core.JsonSchema;
import com.oryxos.core.OryxTool;
import com.oryxos.core.ToolResult;
import com.oryxos.tool.ActionType;
import com.oryxos.tool.Sandbox;
import com.oryxos.tool.SandboxAction;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 内置 Tool {@code list_dir}（FR-2）：列出目录条目——execute 首行 {@code sandbox.enforce(FILE_READ, path)} 先于
 * IO（坑十）；非目录 → 明确报错。条目按名排序、带类型标注（参数规格表）。纯类交付无组件注解（G4-C1）。
 */
public class ListDirTool implements OryxTool {

  private final Sandbox sandbox;

  public ListDirTool(Sandbox sandbox) {
    this.sandbox = sandbox;
  }

  @Override
  public String getName() {
    return "list_dir";
  }

  @Override
  public String getDescription() {
    return "列出指定目录下的条目（文件名 + 类型标注，按名排序）。path 为目录路径（必填）；非目录时明确报错。";
  }

  @Override
  public JsonSchema getInputSchema() {
    return new JsonSchema(
        Map.of(
            "type",
            "object",
            "properties",
            Map.of("path", Map.of("type", "string", "description", "要列出的目录路径")),
            "required",
            List.of("path")));
  }

  @Override
  public ToolResult execute(JsonNode input) {
    Path dir = Path.of(input.get("path").asText());
    sandbox.enforce(new SandboxAction(ActionType.FILE_READ, dir.toString())); // 坑十：enforce 先于 IO
    if (!Files.isDirectory(dir)) {
      return ToolResult.failure("不是目录: " + dir, false);
    }
    try (Stream<Path> entries = Files.list(dir)) {
      StringBuilder sb = new StringBuilder();
      entries
          .sorted(Comparator.comparing(p -> fileNameOf(p)))
          .forEach(
              p ->
                  sb.append(fileNameOf(p))
                      .append(Files.isDirectory(p) ? "（目录）" : "（文件）")
                      .append(System.lineSeparator()));
      return ToolResult.success(sb.toString());
    } catch (IOException e) {
      return ToolResult.failure("列出目录失败: " + dir + "（" + e.getMessage() + "）", false);
    }
  }

  /** getFileName() 对根路径等场景可能返回 null，用路径本身兜底。 */
  private String fileNameOf(Path p) {
    Path fileName = p.getFileName();
    return fileName == null ? p.toString() : fileName.toString();
  }
}
