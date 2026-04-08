package com.aicomm.repository;

import com.aicomm.domain.DeferredTask;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;

public interface DeferredTaskRepository extends JpaRepository<DeferredTask, Long> {

    /**
     * Selects pending tasks that are ready for execution.
     * FOR UPDATE SKIP LOCKED — safe for multiple instances:
     * each instance locks only the rows it picks, others skip them.
     */
    @Query(value = """
            SELECT * FROM deferred_tasks
            WHERE status = 'PENDING' AND execute_at <= :now
            ORDER BY execute_at
            FOR UPDATE SKIP LOCKED
            LIMIT 10
            """, nativeQuery = true)
    List<DeferredTask> findReadyTasks(Instant now);
}
