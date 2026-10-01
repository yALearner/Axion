package com.oryxos.web.api;

import com.oryxos.tool.ToolRegistry;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Tool 查询端点（009-web-service FR-1）——列出 ToolRegistry 全部工具（空表返回空列表）。 */
@SuppressFBWarnings(
    value = "SPRING_ENDPOINT",
    justification =
        "Controller 为薄壳（校验/包装/错误三件事），只读端点无认证属核心阶段明确不做（内网假设），业务逻辑与审计在核心层——FindSecBugs 攻击面提示与宪法"
            + " II/VIII 设计一致")
@RestController
@RequestMapping("/api/v1/tools")
public class ToolApiController {

  private final ToolRegistry toolRegistry;

  public ToolApiController(ToolRegistry toolRegistry) {
    this.toolRegistry = toolRegistry;
  }

  @GetMapping
  public ApiResponse<List<ToolSummary>> list() {
    List<ToolSummary> tools =
        toolRegistry.all().stream()
            .map(t -> new ToolSummary(t.getName(), t.getDescription()))
            .toList();
    return ApiResponse.ok(tools);
  }

  /** Tool 摘要。 */
  public record ToolSummary(String name, String description) {}
}
