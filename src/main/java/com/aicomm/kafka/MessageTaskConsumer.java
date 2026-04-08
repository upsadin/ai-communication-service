package com.aicomm.kafka;

import com.aicomm.idempotency.IdempotencyService;
import com.aicomm.kafka.dto.MessageProcessingTask;
import com.aicomm.schedule.DeferredMessageService;
import com.aicomm.schedule.DeferredTaskExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Thin Kafka consumer — receives tasks, checks idempotency, always defers to DB queue.
 *
 * FIRST_CONTACT tasks always go through deferred_tasks table (even during work hours)
 * so that DeferredTaskExecutor can apply rate limiting.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MessageTaskConsumer {

    private final IdempotencyService idempotencyService;
    private final DeferredMessageService deferredMessageService;

    @KafkaListener(
            topics = "${app.kafka.topic.message-tasks}",
            groupId = "${spring.kafka.consumer.group-id}"
    )
    public void consume(ConsumerRecord<String, MessageProcessingTask> record,
                        Acknowledgment ack) {
        var task = record.value();
        var sourceId = task.sourceId();

        log.info("Received task: sourceId={}, ref={}", sourceId, task.ref());

        if (idempotencyService.isAlreadyProcessed(sourceId)) {
            log.info("Duplicate sourceId={}, skipping", sourceId);
            ack.acknowledge();
            return;
        }

        try {
            idempotencyService.markProcessed(sourceId);
            ack.acknowledge();

            // Always queue — rate limiting is handled by DeferredTaskExecutor
            deferredMessageService.deferNow(DeferredTaskExecutor.TYPE_FIRST_CONTACT, task);

        } catch (Exception e) {
            log.error("Failed to process task sourceId={}: {}", sourceId, e.getMessage(), e);
        }
    }
}
