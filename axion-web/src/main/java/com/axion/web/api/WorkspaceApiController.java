package com.axion.web.api;

import com.axion.core.AgentLifecycleService;
import com.axion.core.AgentStore;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 工作区文件浏览/编辑端点（012-agent-mgmt FR-6/5.2.3）——管理台「文件」tab 的读面。
 *
 * <p>**坑十二**（唯一安全要点）：path 解析为绝对路径后 {@code normalize()} 校验必须落在 {@code .axion/} 内 （{@code
 * startsWith(root)}），越界 400；**坑十三**：编辑 {@code agents/<name>/AGENT.md} 显式走 {@link
 * AgentLifecycleService#update} 重注册（macOS WatchService 不监听子目录内文件改动），其余文件直接写盘。
 */
@SuppressFBWarnings(
    value = "SPRING_ENDPOINT",
    justification =
        "Controller 为薄壳（校验/包装/错误三件事），只读端点无认证属核心阶段明确不做（内网假设），防穿越校验在本类——FindSecBugs 攻击面提示与宪法"
            + " II/VIII 设计一致")
@RestController
@RequestMapping("/api/v1/workspace")
public class WorkspaceApiController {

  private final AgentStore agentStore;
  private final AgentLifecycleService lifecycle;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "AgentStore/AgentLifecycleService 为装配处注入的单例（只读使用、不暴露引用），004 WebhookNotifyAdapter 同款先例")
  public WorkspaceApiController(AgentStore agentStore, AgentLifecycleService lifecycle) {
    this.agentStore = agentStore;
    this.lifecycle = lifecycle;
  }

  /** 目录树：agents/（每个 Agent 一个目录，可展开）+ archive/。 */
  @GetMapping("/tree")
  public ApiResponse<FileNode> tree() {
    FileNode root = new FileNode(".axion", ".axion", "dir", new ArrayList<>());
    FileNode agents = dirNode("agents", agentStore.agentsRoot());
    agents.children().addAll(listChildren(agentStore.agentsRoot(), "agents"));
    FileNode archive = dirNode("archive", agentStore.archiveRoot());
    archive.children().addAll(listChildren(agentStore.archiveRoot(), "archive"));
    root.children().add(agents);
    root.children().add(archive);
    return ApiResponse.ok(root);
  }

  /** 读文件（防穿越，坑十二）。 */
  @GetMapping("/file")
  public ApiResponse<FileContentResponse> file(@RequestParam("path") String path) {
    Path resolved = resolveSafe(path);
    if (!Files.isRegularFile(resolved)) {
      throw new InvalidRequestException("文件不存在: " + path);
    }
    try {
      return ApiResponse.ok(
          new FileContentResponse(Files.readString(resolved, StandardCharsets.UTF_8)));
    } catch (IOException e) {
      throw new IllegalStateException("读取文件失败: " + path, e);
    }
  }

  /** 写文件（5.2.3，防穿越坑十二；AGENT.md 走 update 重注册坑十三）。 */
  @PostMapping("/file")
  public ApiResponse<Void> writeFile(@RequestBody WorkspaceFileRequest request) {
    Path resolved = resolveSafe(request.path());
    if (isAgentMarkdown(resolved)) {
      String name = agentStore.agentsRoot().relativize(resolved).getName(0).toString();
      lifecycle.update(name, request.content()); // 坑十三：显式重注册（schedules 变更先注销旧句柄）
      return ApiResponse.ok(null);
    }
    try {
      Path parent = resolved.getParent(); // 单次取值入局部变量再判空（011 实录）
      if (parent != null) {
        Files.createDirectories(parent);
      }
      Files.writeString(resolved, request.content(), StandardCharsets.UTF_8);
      return ApiResponse.ok(null);
    } catch (IOException e) {
      throw new IllegalStateException("写文件失败: " + request.path(), e);
    }
  }

  /** 坑十二：路径防穿越——normalize 后必须落在工作区内。 */
  private Path resolveSafe(String path) {
    if (path == null || path.isBlank()) {
      throw new InvalidRequestException("path 不能为空");
    }
    Path root = agentStore.workspaceRoot();
    Path resolved = root.resolve(path).normalize();
    if (!resolved.startsWith(root)) {
      throw new InvalidRequestException("路径越界: " + path);
    }
    return resolved;
  }

  private boolean isAgentMarkdown(Path resolved) {
    Path fileName = resolved.getFileName(); // 单次取值入局部变量再判空（011 实录）
    return resolved.startsWith(agentStore.agentsRoot())
        && fileName != null
        && "AGENT.md".equals(fileName.toString())
        && agentStore.agentsRoot().relativize(resolved).getNameCount() == 2;
  }

  private FileNode dirNode(String name, Path path) {
    return new FileNode(name, relative(path), "dir", new ArrayList<>());
  }

  private List<FileNode> listChildren(Path dir, String prefix) {
    if (!Files.isDirectory(dir)) {
      return List.of();
    }
    try (var stream = Files.list(dir)) {
      return stream
          .sorted(Comparator.comparing(Path::toString))
          .map(p -> nodeOf(p, prefix))
          .toList();
    } catch (IOException e) {
      throw new IllegalStateException("列目录失败: " + dir, e);
    }
  }

  private FileNode nodeOf(Path path, String prefix) {
    Path fileName = path.getFileName(); // 单次取值入局部变量再判空（011 实录）
    String name = fileName == null ? "" : fileName.toString();
    String rel = relative(path);
    if (Files.isDirectory(path)) {
      FileNode node = new FileNode(name, rel, "dir", new ArrayList<>());
      node.children().addAll(listChildren(path, prefix + "/" + name));
      return node;
    }
    return new FileNode(name, rel, "file", List.of());
  }

  private String relative(Path path) {
    Path root = agentStore.workspaceRoot();
    if (path.startsWith(root)) {
      return root.relativize(path).toString().replace('\\', '/');
    }
    Path fileName = path.getFileName(); // 单次取值入局部变量再判空（011 实录）
    return fileName == null ? path.toString() : fileName.toString();
  }

  /** 目录树节点（children 为树组装期可变列表——一次性序列化传输）。 */
  @SuppressFBWarnings(value = "EI_EXPOSE_REP", justification = "DTO 一次性序列化传输——011 先例同款抑制")
  public record FileNode(String name, String path, String type, List<FileNode> children) {}

  /** 读文件响应。 */
  public record FileContentResponse(String content) {}

  /** 写文件请求（5.2.3）。 */
  public record WorkspaceFileRequest(String path, String content) {}
}
