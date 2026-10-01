package com.oryxos.storage;

import org.springframework.data.jpa.repository.JpaRepository;

/** scheduled_tasks 仓储——task_id 主键查；010-scheduler-mgmt 交付。 */
public interface ScheduledTaskRepository extends JpaRepository<ScheduledTask, String> {}
