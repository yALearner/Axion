package com.axion.web.api;

import com.axion.core.AgentLifecycleService;
import com.axion.core.AgentService;
import com.axion.core.MemoryService;
import com.axion.core.Profile;
import com.axion.core.ProfileRegistry;
import com.axion.core.Session;
import com.axion.core.SessionManager;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Agent 管理端点（009 无状态调用 + 012-agent-mgmt 扩展为完整生命周期，课件 5.3 全表）。
 *
 * <p>薄壳三件事：404 预检（坑十八重名预检也在此——一个目录都不写）→ 委托 {@link AgentLifecycleService} → 信封包装。 错误语义（2.5
 * 口径）：已存在/非法/生成非法 → 400、不存在 → 404、生成模型未配置 → 503（经 {@link AgentManagementException} 携带 code
 * 区分）。固定会话两端点（5.2.2）：channel=admin、user=console、 getOrCreate 幂等——session_id 拼接只在
 * SessionManager（不变量④）。
 */
@SuppressFBWarnings(
    value = "SPRING_ENDPOINT",
    justification =
        "Controller 为薄壳（校验/包装/错误三件事），只读端点无认证属核心阶段明确不做（内网假设），业务逻辑与审计在核心层——FindSecBugs 攻击面提示与宪法"
            + " II/VIII 设计一致")
@RestController
@RequestMapping("/api/v1/agents")
public class AgentApiController {

  private static final long PROCESS_TIMEOUT_SECONDS = 60;

  private final AgentService agentService;
  private final SessionManager sessionManager;
  private final ProfileRegistry profileRegistry;
  private final AgentLifecycleService lifecycle;
  private final MemoryService memoryService;

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "AgentService/SessionManager/ProfileRegistry/AgentLifecycleService/MemoryService"
              + " 为装配处注入的单例（只读使用、不暴露引用）——AgentScheduler 同款先例")
  public AgentApiController(
      AgentService agentService,
      SessionManager sessionManager,
      ProfileRegistry profileRegistry,
      AgentLifecycleService lifecycle,
      MemoryService memoryService) {
    this.agentService = agentService;
    this.sessionManager = sessionManager;
    this.profileRegistry = profileRegistry;
    this.lifecycle = lifecycle;
    this.memoryService = memoryService;
  }

  /** 012：创建（四步编排在 lifecycle——重名第一步就拒、失败回滚）。 */
  @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
  public ApiResponse<AgentView> create(@RequestBody CreateAgentRequest request) {
    if (profileRegistry.exists(request.name())) {
      throw AgentManagementException.exists(request.name()); // 坑十八：预检先拒，一个目录都不写
    }
    try {
      return ApiResponse.ok(
          AgentView.from(lifecycle.create(request.name(), request.description())));
    } catch (IllegalArgumentException e) {
      throw AgentManagementException.invalid(
          e.getMessage() == null ? "Agent 定义非法" : e.getMessage());
    }
  }

  /** 012：列表（GET /agents）。 */
  @GetMapping
  public ApiResponse<List<AgentView>> list() {
    return ApiResponse.ok(profileRegistry.list().stream().map(AgentView::from).toList());
  }

  /** 012：详情（GET /agents/{name}）。 */
  @GetMapping("/{name}")
  public ApiResponse<AgentView> get(@PathVariable String name) {
    return ApiResponse.ok(AgentView.from(findOrThrow(name)));
  }

  /** 012：更新（整份 AGENT.md 覆写；schedules 变更先注销旧句柄再注册新——lifecycle 统一处理）。 */
  @PutMapping(path = "/{name}", consumes = MediaType.APPLICATION_JSON_VALUE)
  public ApiResponse<AgentView> update(
      @PathVariable String name, @RequestBody UpdateAgentRequest request) {
    findOrThrow(name);
    try {
      return ApiResponse.ok(AgentView.from(lifecycle.update(name, request.agentMd())));
    } catch (IllegalArgumentException e) {
      throw AgentManagementException.invalid(
          e.getMessage() == null ? "Agent 定义非法" : e.getMessage());
    }
  }

  /** 012：删除（顺序钉死坑十：注销定时 → 移出索引 → 归档 .axion/archive/）。 */
  @DeleteMapping("/{name}")
  public ApiResponse<Void> delete(@PathVariable String name) {
    findOrThrow(name);
    lifecycle.delete(name);
    return ApiResponse.ok(null);
  }

  /** 012：per-agent 记忆（5.2.1，只读——由 save_memory 写入）。 */
  @GetMapping("/{name}/memory")
  public ApiResponse<MemoryResponse> memory(@PathVariable String name) {
    findOrThrow(name);
    return ApiResponse.ok(new MemoryResponse(memoryService.readAll(name)));
  }

  /** 012：固定会话（5.2.2）——admin:console:<name> getOrCreate 幂等，恒为同一条。 */
  @GetMapping("/{name}/session")
  public ApiResponse<SessionView> session(@PathVariable String name) {
    findOrThrow(name);
    Session session = sessionManager.getOrCreate("admin", "console", name);
    List<com.axion.core.Message> messages = session.messages();
    int from = Math.max(0, messages.size() - 100); // 最近 ≤100 条（5.2.2 口径）
    return ApiResponse.ok(
        new SessionView(
            session.id(), session.profileName(), messages.subList(from, messages.size())));
  }

  /** 012：向固定会话发消息、触发 ReAct（与 invoke 同一引擎，宪法 VIII——落在固定会话里累积上下文）。 */
  @PostMapping(path = "/{name}/session/messages", consumes = MediaType.APPLICATION_JSON_VALUE)
  public ApiResponse<InvokeResponse> sendMessage(
      @PathVariable String name, @RequestBody InvokeRequest request) {
    findOrThrow(name);
    Session session = sessionManager.getOrCreate("admin", "console", name);
    String reply = runWithTimeout(session, request.content());
    return ApiResponse.ok(new InvokeResponse(reply));
  }

  /** 012：按描述重生成文件（5.2.4）——草稿预览可改，不落盘不注册；LLM 调用落 llm_calls（宪法 V，LlmGateway 契约）。 */
  @PostMapping(path = "/{name}/generate-files", consumes = MediaType.APPLICATION_JSON_VALUE)
  public ApiResponse<GenerateFilesResponse> generateFiles(
      @PathVariable String name, @RequestBody GenerateFilesRequest request) {
    findOrThrow(name);
    Session session = sessionManager.getOrCreate("admin", "console", name); // 审计关联（不变量④）
    try {
      Map<String, String> files =
          lifecycle.generateFiles(session.id(), name, request.description());
      return ApiResponse.ok(new GenerateFilesResponse(files));
    } catch (IllegalArgumentException e) {
      String message = e.getMessage() == null ? "" : e.getMessage();
      if (message.startsWith(AgentLifecycleService.MODEL_MISSING_MESSAGE)) {
        throw AgentManagementException.authorModelMissing(); // 坑十五：可读 503
      }
      throw AgentManagementException.generateInvalid(message); // 坑十四：非法 400 可读原因
    }
  }

  /** 012：保存一组（可能被用户改过的）文件（5.2.4）——先校验再写盘、写入即生效（重注册）。 */
  @PostMapping(path = "/{name}/files", consumes = MediaType.APPLICATION_JSON_VALUE)
  public ApiResponse<AgentView> saveFiles(
      @PathVariable String name, @RequestBody SaveFilesRequest request) {
    findOrThrow(name);
    try {
      return ApiResponse.ok(AgentView.from(lifecycle.saveFiles(name, request.files())));
    } catch (IllegalArgumentException e) {
      throw AgentManagementException.invalid(e.getMessage() == null ? "文件非法" : e.getMessage());
    }
  }

  /** 无状态调用：校验 Agent 存在 → 一次性 Session → 跑 ReAct → 返回答复。 */
  @PostMapping(path = "/{name}/invoke", consumes = MediaType.APPLICATION_JSON_VALUE)
  public ApiResponse<InvokeResponse> invoke(
      @PathVariable String name, @RequestBody InvokeRequest request) {
    if (profileRegistry.findByName(name).isEmpty()) {
      throw new ResourceNotFoundException("Agent 不存在: " + name);
    }
    Session oneShot = sessionManager.getOrCreate("web", "invoke", name); // 三元组固定（⑨ 实现级明确）
    String reply = runWithTimeout(oneShot, request.content());
    return ApiResponse.ok(new InvokeResponse(reply));
  }

  /** ⑨c 60 秒超时：同 SessionApiController 口径——超时后任务体继续后台跑完、审计照常；业务异常按原类型上抛。 */
  @SuppressFBWarnings(
      value = "THROWS_METHOD_THROWS_RUNTIMEEXCEPTION",
      justification =
          "ExecutionException unwrap 后按原类型重抛业务异常——GlobalExceptionHandler 需按原异常类型映射，刻意重抛"
              + " RuntimeException（⑨c 口径）")
  private String runWithTimeout(Session session, String content) {
    FutureTask<String> task = new FutureTask<>(() -> agentService.process(session, content));
    Thread.ofVirtual().start(task);
    try {
      return task.get(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    } catch (TimeoutException e) {
      throw new AgentTimeoutException("Agent 调用超时（60 秒上限）；任务体继续后台跑完、审计照常落账");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("请求线程被中断", e);
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof RuntimeException runtimeException) {
        throw runtimeException;
      }
      throw new IllegalStateException("Agent 调用失败", cause);
    }
  }

  /** 无状态调用请求。 */
  public record InvokeRequest(String content) {}

  /** 无状态调用响应。 */
  public record InvokeResponse(String reply) {}

  /** 012：创建请求（{name, description} → 模板脚手架——5.1 拍板）。 */
  public record CreateAgentRequest(String name, String description) {}

  /** 012：更新请求（整份 AGENT.md 覆写，语义最简——F1 拍板）。 */
  public record UpdateAgentRequest(String agentMd) {}

  /** 012：Agent 视图（不对前端暴露 api_key）。 */
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification = "DTO 一次性序列化传输，构造入参来自 Profile 防御性拷贝的访问器——011 先例同款抑制")
  public record AgentView(
      String name,
      String description,
      String providerName,
      String model,
      List<String> tools,
      List<Profile.Schedule> schedules) {

    public static AgentView from(Profile profile) {
      return new AgentView(
          profile.name(),
          profile.description(),
          profile.provider() == null ? null : profile.provider().name(),
          profile.provider() == null ? null : profile.provider().model(),
          profile.tools(),
          profile.schedules());
    }
  }

  /** 012：生成请求（一句描述）。 */
  public record GenerateFilesRequest(String description) {}

  /** 012：生成响应（相对路径 → 内容；前端每文件可编辑预览）。 */
  @SuppressFBWarnings(value = "EI_EXPOSE_REP", justification = "DTO 一次性序列化传输——011 先例同款抑制")
  public record GenerateFilesResponse(Map<String, String> files) {}

  /** 012：保存请求（可能被用户改过的文件集）。 */
  @SuppressFBWarnings(value = "EI_EXPOSE_REP", justification = "DTO 一次性反序列化传输——011 先例同款抑制")
  public record SaveFilesRequest(Map<String, String> files) {}

  /** 012：固定会话视图（sessionId + profileName + 最近 ≤100 条）。 */
  @SuppressFBWarnings(value = "EI_EXPOSE_REP", justification = "DTO 一次性序列化传输——011 先例同款抑制")
  public record SessionView(
      String sessionId, String profileName, List<com.axion.core.Message> messages) {}

  /** 012：per-agent 记忆响应（5.2.1，只读）。 */
  public record MemoryResponse(String content) {}

  /** 404 预检（查/改/删/会话/记忆/生成/保存共用的薄壳校验）。 */
  private Profile findOrThrow(String name) {
    return profileRegistry
        .findByName(name)
        .orElseThrow(() -> AgentManagementException.notFound(name));
  }
}
