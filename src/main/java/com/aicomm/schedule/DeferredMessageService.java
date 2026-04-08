package com.aicomm.schedule;

import com.aicomm.domain.DeferredTask;
import com.aicomm.repository.DeferredTaskRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Persists deferred tasks in the database instead of in-memory scheduler.
 * Tasks are picked up by DeferredTaskExecutor on schedule.
 *
 * Survives application restarts (critical for Heroku where dynos restart frequently).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeferredMessageService {

    private final WorkScheduleService workScheduleService;
    private final DeferredTaskRepository taskRepository;
    private final ObjectMapper objectMapper;

    /**
     * Executes task immediately if within working hours,
     * otherwise persists it in DB for later execution.
     *
     * @param taskType  type identifier (FIRST_CONTACT, REPLY, FOLLOW_UP)
     * @param payload   serializable payload object (will be stored as JSON)
     * @param immediate runnable to execute immediately if within working hours
     * @return true if executed immediately, false if deferred to DB
     */
    public boolean executeOrDefer(String taskType, Object payload, Runnable immediate) {
        if (workScheduleService.isWorkingHours()) {
            immediate.run();
            return true;
        }

        defer(taskType, payload);
        return false;
    }

    /**
     * Saves a deferred task to DB, scheduled for next work-start + random delay.
     */
    @Transactional
    public void defer(String taskType, Object payload) {
        try {
            long delayMs = workScheduleService.calculateDeferralDelay();
            var executeAt = Instant.now().plusMillis(delayMs);
            var payloadJson = objectMapper.writeValueAsString(payload);

            var task = new DeferredTask(taskType, payloadJson, executeAt);
            taskRepository.save(task);

            log.info("Deferred task type={} to {} (in {}min)", taskType, executeAt, delayMs / 60000);
        } catch (Exception e) {
            log.error("Failed to defer task type={}: {}", taskType, e.getMessage(), e);
        }
    }

    /**
     * Saves a task for immediate execution by next poll cycle (execute_at = now).
     * Used for FIRST_CONTACT which must always go through the queue for rate limiting.
     */
    @Transactional
    public void deferNow(String taskType, Object payload) {
        try {
            var payloadJson = objectMapper.writeValueAsString(payload);
            var task = new DeferredTask(taskType, payloadJson, Instant.now());
            taskRepository.save(task);
            log.info("Queued task type={} for immediate execution", taskType);
        } catch (Exception e) {
            log.error("Failed to queue task type={}: {}", taskType, e.getMessage(), e);
        }
    }

    /**
     * Saves a deferred task with explicit delay (e.g. for follow-up "напиши через 5 минут").
     */
    @Transactional
    public void deferWithDelay(String taskType, Object payload, long delayMinutes) {
        try {
            var executeAt = Instant.now().plusSeconds(delayMinutes * 60);
            var payloadJson = objectMapper.writeValueAsString(payload);

            var task = new DeferredTask(taskType, payloadJson, executeAt);
            taskRepository.save(task);

            log.info("Deferred task type={} for {} minutes", taskType, delayMinutes);
        } catch (Exception e) {
            log.error("Failed to defer task type={}: {}", taskType, e.getMessage(), e);
        }
    }
}
