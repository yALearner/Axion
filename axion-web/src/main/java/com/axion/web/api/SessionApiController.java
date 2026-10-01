package com.oryxos.web.api;

import com.oryxos.core.AgentService;
import com.oryxos.core.Session;
import com.oryxos.core.SessionManager;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 会话管理端点（009-web-service FR-1，课件 26 §三骨架）——薄 Controller：参数校验、响应包装、错误处理三件事之外一概不碰； 发消息走 {@link
 * AgentService#process}，与 CLI 完全同一入口（宪法 VIII）。
 *
 * <p>防呆限制（FR-4）：单条消息 ≤ 32KB；历史返回最近 100 条。60 秒超时（⑨c）：{@link #runWithTimeout} 用 {@link FutureTask} +
 * virtual thread 承载（JDK 原生、无自建线程池——宪法 VII 不变量 ⑤）；超时后任务体继续跑完不可杀， 审计照常落账。
 */
@SuppressFBWarnings(
    value = "SPRING_ENDPOINT",
    justification =
        "Controller 为薄壳（校验/包装/错误三件事），只读端点无认证属核心阶段明确不做（内网假设），业务逻辑与审计在核心层——FindSecBugs 攻击面提示与宪法"
            + " II/VIII 设计一致")
@RestController
@RequestMapping("/api/v1/sessions")
public class SessionApiController {

  private static final int MAX_MESSAGE_BYTES = 32 * 1024;
  private static final int MAX_HISTORY_MESSAGES = 100;
  private static final long PROCESS_TIMEOUT_SECONDS = 60;

  private final AgentService agentService;
  private final SessionManager sessionManager;

  public SessionApiController(AgentService agentService, SessionManager sessionManager) {
    this.agentService = agentService;
    this.sessionManager = sessionManager;
  }

  /** 创建会话：三元组 getOrCreate，返回 session_id。 */
  @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
  public ApiResponse<SessionCreatedResponse> create(@RequestBody CreateSessionRequest request) {
    Session session =
        sessionManager.getOrCreate(request.channel(), request.userId(), request.profileName());
    return ApiResponse.ok(new SessionCreatedResponse(session.id()));
  }

  /** 发消息：32KB 防呆 → 按 id 取会话 → 与 CLI 同一入口跑 ReAct → 返回答复。 */
  @PostMapping(path = "/{id}/messages", consumes = MediaType.APPLICATION_JSON_VALUE)
  public ApiResponse<MessageResponse> send(
      @PathVariable String id, @RequestBody MessageRequest request) {
    if (request.content() == null || request.content().isBlank()) {
      throw new InvalidRequestException("消息为空");
    }
    if (request.content().length() > MAX_MESSAGE_BYTES) {
      throw new InvalidRequestException("消息超过 32KB 上限");
    }
    Session session = sessionManager.get(id).orElseThrow(() -> new SessionNotFoundException(id));
    String reply = runWithTimeout(session, request.content());
    return ApiResponse.ok(new MessageResponse(reply));
  }

  /** 查历史：最近 100 条。 */
  @GetMapping(path = "/{id}")
  public ApiResponse<SessionHistoryResponse> history(@PathVariable String id) {
    Session session = sessionManager.get(id).orElseThrow(() -> new SessionNotFoundException(id));
    List<com.oryxos.core.Message> messages = session.messages();
    List<com.oryxos.core.Message> recent =
        messages.size() > MAX_HISTORY_MESSAGES
            ? messages.subList(messages.size() - MAX_HISTORY_MESSAGES, messages.size())
            : messages;
    return ApiResponse.ok(new SessionHistoryResponse(session.id(), recent));
  }

  /** 归档：SessionManager.archive（拍板 A）。 */
  @DeleteMapping(path = "/{id}")
  public ApiResponse<Void> archive(@PathVariable String id) {
    sessionManager.archive(id);
    return ApiResponse.ok(null);
  }

  /**
   * ⑨c 60 秒超时：FutureTask + virtual thread 承载任务体（无自建线程池）；超时抛 {@link AgentTimeoutException}→504，
   * 任务体继续后台跑完（LLM 调用不可强制中断）、审计照常落账；业务异常 unwrap 上抛由 GlobalExceptionHandler 按原类型映射。
   */
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
        throw runtimeException; // 业务异常按原类型上抛 → GlobalExceptionHandler 映射
      }
      throw new IllegalStateException("Agent 调用失败", cause);
    }
  }

  /** 创建会话请求（三元组）。 */
  public record CreateSessionRequest(String channel, String userId, String profileName) {}

  /** 创建会话响应。 */
  public record SessionCreatedResponse(String sessionId) {}

  /** 发消息请求。 */
  public record MessageRequest(String content) {}

  /** 发消息响应。 */
  public record MessageResponse(String reply) {}

  /** 历史响应。 */
  public record SessionHistoryResponse(String sessionId, List<com.oryxos.core.Message> messages) {
    public SessionHistoryResponse {
      // 防御性拷贝（007 先例）：不暴露可变列表的内部表示——Jackson 序列化读 accessor 不受影响
      messages = messages == null ? java.util.List.of() : java.util.List.copyOf(messages);
    }
  }
}
