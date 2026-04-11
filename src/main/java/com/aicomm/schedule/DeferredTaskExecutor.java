package com.aicomm.schedule;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.aicomm.conversation.ConversationService;
import com.aicomm.conversation.FirstContactService;
import com.aicomm.conversation.ScheduledFollowUpService;
import com.aicomm.domain.ConversationStatus;
import com.aicomm.domain.DeferredTask;
import com.aicomm.kafka.dto.MessageProcessingTask;
import com.aicomm.repository.ConversationRepository;
import com.aicomm.repository.DeferredTaskRepository;
import com.aicomm.telegram.TelegramRateLimiter;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Polls deferred_tasks table and executes tasks that are due.
 *
 * Uses SELECT ... FOR UPDATE SKIP LOCKED — safe for multiple instances (Heroku dynos).
 * Each task is processed in its own transaction to release the row lock quickly.
 *
 * FIRST_CONTACT tasks are rate-limited. REPLY/FOLLOW_UP execute without limit.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeferredTaskExecutor {

    public static final String TYPE_FIRST_CONTACT = "FIRST_CONTACT";
    public static final String TYPE_REPLY = "REPLY";
    public static final String TYPE_FOLLOW_UP = "FOLLOW_UP";
    public static final String TYPE_TEST_TIMEOUT = "TEST_TIMEOUT";
    private static final int MAX_ATTEMPTS = 3;

    private final DeferredTaskRepository taskRepository;
    private final ObjectMapper objectMapper;
    private final FirstContactService firstContactService;
    private final ScheduledFollowUpService followUpService;
    private final ConversationService conversationService;
    private final ConversationRepository conversationRepository;
    private final WorkScheduleService workScheduleService;
    private final TelegramRateLimiter rateLimiter;
    private final DeferredMessageService deferredMessageService;
    private final TransactionTemplate transactionTemplate;

    @Scheduled(fixedRateString = "${app.schedule.poll-interval-ms:60000}")
    public void pollAndExecute() {
        if (!workScheduleService.isWorkingHours()) return;

        // SELECT FOR UPDATE SKIP LOCKED — each instance gets its own set of tasks
        var readyTasks = transactionTemplate.execute(status ->
                taskRepository.findReadyTasks(Instant.now()));

        if (readyTasks == null || readyTasks.isEmpty()) return;

        log.info("Found {} deferred tasks ready for execution", readyTasks.size());

        // Process replies and follow-ups first (no rate limit)
        readyTasks.stream()
                .filter(t -> !TYPE_FIRST_CONTACT.equals(t.getTaskType()))
                .forEach(this::executeTaskInTransaction);

        // Process first contacts with rate limiting
        var firstContacts = readyTasks.stream()
                .filter(t -> TYPE_FIRST_CONTACT.equals(t.getTaskType()))
                .toList();
        processFirstContactsWithRateLimit(firstContacts);
    }

    private void processFirstContactsWithRateLimit(List<DeferredTask> tasks) {
        for (var task : tasks) {
            long waitSec = rateLimiter.acquireOrGetDelay();

            if (waitSec < 0) {
                transactionTemplate.executeWithoutResult(status -> {
                    task.setExecuteAt(Instant.now().plusSeconds(86400));
                    taskRepository.save(task);
                });
                log.warn("Daily limit reached, pushing task id={} to tomorrow", task.getId());
                continue;
            }

            if (waitSec > 0) {
                transactionTemplate.executeWithoutResult(status -> {
                    task.setExecuteAt(Instant.now().plusSeconds(waitSec));
                    taskRepository.save(task);
                });
                log.debug("Rate limited: task id={} rescheduled in {}s", task.getId(), waitSec);
                return;
            }

            executeTaskInTransaction(task);
            return; // Only one first contact per poll cycle
        }
    }

    /**
     * Executes a single task in its own transaction.
     * Row lock from SELECT FOR UPDATE is released after this transaction commits.
     */
    private void executeTaskInTransaction(DeferredTask task) {
        transactionTemplate.executeWithoutResult(status -> executeTask(task));
    }

    private void executeTask(DeferredTask task) {
        try {
            log.info("Executing deferred task id={}, type={}", task.getId(), task.getTaskType());

            switch (task.getTaskType()) {
                case TYPE_FIRST_CONTACT -> {
                    var messageTask = objectMapper.readValue(task.getPayload(), MessageProcessingTask.class);
                    firstContactService.initiateContact(messageTask);
                }
                case TYPE_REPLY, TYPE_FOLLOW_UP -> {
                    var payload = objectMapper.readTree(task.getPayload());
                    var conversationId = payload.get("conversationId").asLong();
                    followUpService.sendFollowUpForConversation(conversationId);
                }
                case TYPE_TEST_TIMEOUT -> {
                    var payload = objectMapper.readTree(task.getPayload());
                    var conversationId = payload.get("conversationId").asLong();
                    handleTestTimeout(conversationId);
                }
                case "TEST_TIMEOUT_FINAL" -> {
                    var payload = objectMapper.readTree(task.getPayload());
                    var conversationId = payload.get("conversationId").asLong();
                    handleTestTimeoutFinal(conversationId);
                }
                default -> log.warn("Unknown deferred task type: {}", task.getTaskType());
            }

            task.setStatus("COMPLETED");
            taskRepository.save(task);

        } catch (Exception e) {
            task.setAttempts(task.getAttempts() + 1);
            task.setError(e.getMessage());

            if (task.getAttempts() >= MAX_ATTEMPTS) {
                task.setStatus("FAILED");
                log.error("Deferred task id={} failed after {} attempts: {}",
                        task.getId(), MAX_ATTEMPTS, e.getMessage());
            } else {
                task.setExecuteAt(Instant.now().plusSeconds(300));
                log.warn("Deferred task id={} failed (attempt {}), retrying: {}",
                        task.getId(), task.getAttempts(), e.getMessage());
            }
            taskRepository.save(task);
        }
    }

    /**
     * Handles test task timeout: if candidate hasn't responded, send a reminder.
     * If this is the second timeout (reminder already sent), mark as TIMED_OUT.
     */
    private void handleTestTimeout(long conversationId) {
        var conversationOpt = conversationRepository.findById(conversationId);
        if (conversationOpt.isEmpty()) return;

        var conversation = conversationOpt.get();

        // If candidate already responded (status changed from TEST_SENT), skip
        if (conversation.getStatus() != ConversationStatus.TEST_SENT) {
            log.info("Test timeout for conversationId={} skipped — status is {}", conversationId, conversation.getStatus());
            return;
        }

        // Send reminder via follow-up mechanism
        followUpService.sendFollowUpForConversation(conversationId);

        // Schedule final timeout (1 day) → TIMED_OUT
        deferredMessageService.deferWithDelay(
                TYPE_TEST_TIMEOUT + "_FINAL",
                Map.of("conversationId", conversationId),
                24 * 60 // 1 day
        );

        log.info("Test timeout reminder sent for conversationId={}", conversationId);
    }

    private void handleTestTimeoutFinal(long conversationId) {
        var conversationOpt = conversationRepository.findById(conversationId);
        if (conversationOpt.isEmpty()) return;

        var conversation = conversationOpt.get();
        if (conversation.getStatus() != ConversationStatus.TEST_SENT) {
            log.info("Final test timeout for conversationId={} skipped — status is {}", conversationId, conversation.getStatus());
            return;
        }

        conversationService.updateStatus(conversation.getId(), ConversationStatus.TIMED_OUT);
        log.info("Conversation id={} timed out — no response to test task", conversationId);
    }
}
