package com.oryxos.web.api;

import com.oryxos.core.AgentService;
import com.oryxos.core.ProfileRegistry;
import com.oryxos.core.Session;
import com.oryxos.core.SessionManager;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 无状态调用端点（009-web-service FR-1，课件 §四）——把 Agent 当函数用：临时建一次性 Session、跑完 ReAct 返回。
 *
 * <p>⑨ 实现级明确：一次性 Session 三元组固定 {@code ("web", "invoke", agentName)}、跑完不缓存——审计表 session_id
 * 可追溯且不污染长会话。 60 秒超时同 {@link SessionApiController} 口径（⑨c）。
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

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "AgentService/SessionManager/ProfileRegistry 为装配处注入的单例（只读使用、不暴露引用）——AgentScheduler 同款先例")
  public AgentApiController(
      AgentService agentService, SessionManager sessionManager, ProfileRegistry profileRegistry) {
    this.agentService = agentService;
    this.sessionManager = sessionManager;
    this.profileRegistry = profileRegistry;
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
}
