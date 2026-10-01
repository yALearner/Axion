package com.oryxos.storage;

import java.time.Instant;

/**
 * 定时任务执行历史视图（010-scheduler-mgmt，契约值对象）。成功失败都记（宪法 V 同源）； POST /schedules/{id}/run
 * 的返回体与本视图同形状（Clarifications 2026-09-09）。
 *
 * @param id 历史条目主键（持久化后才有值；recordExecution 入参可为 null）
 * @param sessionId 本次触发的钟推 Session（channel/user 固定 scheduler）
 * @param startedAt 开始时刻（UTC）
 * @param errorMessage 失败时人可读消息（非堆栈，NFR-003）
 * @param durationMs 执行耗时（毫秒）
 */
public record TaskExecutionView(
    Long id,
    String taskId,
    String sessionId,
    Instant startedAt,
    boolean success,
    String errorMessage,
    Long durationMs) {}
