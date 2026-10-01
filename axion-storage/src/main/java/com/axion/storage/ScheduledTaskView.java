package com.oryxos.storage;

import java.time.Instant;

/**
 * 定时任务状态视图（010-scheduler-mgmt，契约值对象——JPA 实体不上浮）。
 *
 * <p>时间字段统一 UTC Instant（Clarifications 2026-09-09）；zone 字段随行，展示层按任务 zone 转本地。
 *
 * @param taskId = frontmatter schedules 条目的 id（全局唯一，跨 Profile 冲突注册时报错）
 * @param lastStatus {@code success} / {@code failure}，未跑过为 null
 * @param runCount 累计真正执行次数（含 runNow）
 */
public record ScheduledTaskView(
    String taskId,
    String profileName,
    String cron,
    String zone,
    String message,
    boolean enabled,
    Instant nextRunAt,
    Instant lastRunAt,
    String lastStatus,
    int runCount) {}
