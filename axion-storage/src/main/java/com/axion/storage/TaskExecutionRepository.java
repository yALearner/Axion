package com.oryxos.storage;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** task_executions 仓储——按 task_id 查历史（最新在前，contracts/ §2）；010-scheduler-mgmt 交付。 */
public interface TaskExecutionRepository extends JpaRepository<TaskExecution, Long> {

  List<TaskExecution> findByTaskIdOrderByIdDesc(String taskId);
}
