package com.aicomm.kafka;

import com.aicomm.idempotency.IdempotencyService;
import com.aicomm.kafka.dto.MessageProcessingTask;
import com.aicomm.schedule.DeferredMessageService;
import com.aicomm.schedule.DeferredTaskExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MessageTaskConsumerTest {

    @Mock private IdempotencyService idempotencyService;
    @Mock private DeferredMessageService deferredMessageService;
    @Mock private Acknowledgment ack;

    @InjectMocks
    private MessageTaskConsumer consumer;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void consume_skipsDuplicate() {
        var task = new MessageProcessingTask("ref", "src-dup", null);
        var record = new ConsumerRecord<>("topic", 0, 0L, "key", task);

        when(idempotencyService.isAlreadyProcessed("src-dup")).thenReturn(true);

        consumer.consume(record, ack);

        verify(ack).acknowledge();
        verifyNoInteractions(deferredMessageService);
    }

    @Test
    void consume_marksProcessedAndAcks() {
        var task = new MessageProcessingTask("candidate_java", "src-new", mapper.createObjectNode());
        var record = new ConsumerRecord<>("topic", 0, 0L, "key", task);

        when(idempotencyService.isAlreadyProcessed("src-new")).thenReturn(false);

        consumer.consume(record, ack);

        verify(idempotencyService).markProcessed("src-new");
        verify(ack).acknowledge();
    }

    @Test
    void consume_alwaysQueuesViaDeferNow() {
        var aiResult = mapper.createObjectNode().put("contactId", "123");
        var task = new MessageProcessingTask("candidate_java", "src-1", aiResult);
        var record = new ConsumerRecord<>("topic", 0, 0L, "key", task);

        when(idempotencyService.isAlreadyProcessed("src-1")).thenReturn(false);

        consumer.consume(record, ack);

        verify(deferredMessageService).deferNow(eq(DeferredTaskExecutor.TYPE_FIRST_CONTACT), eq(task));
    }
}
