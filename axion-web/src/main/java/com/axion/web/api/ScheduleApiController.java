package com.oryxos.web.api;

import com.oryxos.core.AgentScheduler;
import com.oryxos.storage.ScheduledTaskStore;
import com.oryxos.storage.ScheduledTaskView;
import com.oryxos.storage.TaskExecutionView;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 定时任务管理四端点（010-scheduler-mgmt FR-5，统一 /api/v1 前缀 + 双信封——009 契约延续）。
 *
 * <p>四端点只做「状态查看 / 立即执行 / 启用停用」，不含定义 CRUD（29/30 节补齐）。语义要点：
 *
 * <ul>
 *   <li>POST run：⑦b 同步等待执行完成（60s 上限 + 504，复用 009 {@link AgentTimeoutException}），返回体 = 本次 {@link
 *       TaskExecutionView}（与 executions 历史条目同形状，Clarifications 2026-09-09）
 *   <li>PUT：仅改 enabled（next_run_at 保留原值，Clarifications 2026-09-09）；请求体多余字段忽略
 *   <li>任务不存在 → 404（{@link ResourceNotFoundException} 单出口）；enabled 缺失 → 400
 * </ul>
 */
@SuppressFBWarnings(
    value = "SPRING_ENDPOINT",
    justification =
        "Controller 为薄壳（校验/包装/错误三件事），核心阶段无认证属明确不做（内网假设），业务逻辑在 core/AgentScheduler 与 storage"
            + " 实现内——FindSecBugs 攻击面提示与宪法 II/VIII 设计一致")
@RestController
@RequestMapping("/api/v1/schedules")
public class ScheduleApiController {

  private static final long PROCESS_TIMEOUT_SECONDS = 60;

  private final AgentScheduler scheduler;
  private final ScheduledTaskStore store;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "AgentScheduler/ScheduledTaskStore 为装配处注入的单例（只读使用、不暴露引用），AgentScheduler 构造器同款先例")
  public ScheduleApiController(AgentScheduler scheduler, ScheduledTaskStore store) {
    this.scheduler = scheduler;
    this.store = store;
  }

  /** 任务列表（按 taskId 升序——contracts/ §1，F3）。 */
  @GetMapping
  public ApiResponse<List<ScheduledTaskView>> list() {
    return ApiResponse.ok(store.list());
  }

  /** 某任务的执行历史（最新在前）。 */
  @GetMapping("/{id}/executions")
  public ApiResponse<List<TaskExecutionView>> executions(@PathVariable String id) {
    requireTask(id);
    return ApiResponse.ok(store.executions(id));
  }

  /** 立即执行（⑦b 同步等待 60s + 504；无视启用状态；与到点触发同锁同入口——⑦a 并发不双跑）。 */
  @PostMapping("/{id}/run")
  public ApiResponse<TaskExecutionView> run(@PathVariable String id) {
    requireTask(id);
    return ApiResponse.ok(runWithTimeout(id));
  }

  /** 启用/停用（仅 enabled 字段；next_run_at 保留原值）。 */
  @PutMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
  public ApiResponse<ScheduledTaskView> update(
      @PathVariable String id, @RequestBody EnableRequest request) {
    requireTask(id);
    if (request.enabled() == null) {
      throw new InvalidRequestException("请求参数非法：enabled 必填");
    }
    store.setEnabled(id, request.enabled());
    return ApiResponse.ok(
        store.list().stream().filter(t -> t.taskId().equals(id)).findFirst().orElseThrow());
  }

  /** 任务不存在 → 404（以 store 列表为存在性真相源——定义登记即入库）。 */
  private void requireTask(String id) {
    if (store.list().stream().noneMatch(t -> t.taskId().equals(id))) {
      throw new ResourceNotFoundException("定时任务不存在: " + id);
    }
  }

  /** ⑦b 60 秒超时：同 AgentApiController 口径——超时后任务体继续后台跑完、审计照常；业务异常按原类型上抛。 */
  @SuppressFBWarnings(
      value = "THROWS_METHOD_THROWS_RUNTIMEEXCEPTION",
      justification =
          "ExecutionException unwrap 后按原类型重抛业务异常——GlobalExceptionHandler 需按原异常类型映射，刻意重抛"
              + " RuntimeException（⑨c 口径）")
  private TaskExecutionView runWithTimeout(String taskId) {
    FutureTask<TaskExecutionView> task = new FutureTask<>(() -> scheduler.runNow(taskId));
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

  /** 启用/停用请求体（多余字段忽略）。 */
  public record EnableRequest(Boolean enabled) {}
}
