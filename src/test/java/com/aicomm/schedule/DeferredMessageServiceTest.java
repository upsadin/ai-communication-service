package com.aicomm.schedule;

import com.aicomm.domain.DeferredTask;
import com.aicomm.repository.DeferredTaskRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DeferredMessageServiceTest {

    @Mock
    private WorkScheduleService workScheduleService;

    @Mock
    private DeferredTaskRepository taskRepository;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private DeferredMessageService deferredMessageService;

    @Test
    void executeOrDefer_runsImmediately_duringWorkHours() {
        when(workScheduleService.isWorkingHours()).thenReturn(true);

        var executed = new AtomicBoolean(false);
        var result = deferredMessageService.executeOrDefer(
                "FIRST_CONTACT", Map.of("test", true), () -> executed.set(true));

        assertThat(result).isTrue();
        assertThat(executed).isTrue();
        verifyNoInteractions(taskRepository);
    }

    @Test
    void executeOrDefer_savesToDB_outsideWorkHours() {
        when(workScheduleService.isWorkingHours()).thenReturn(false);
        when(workScheduleService.calculateDeferralDelay()).thenReturn(60000L);
        when(taskRepository.save(any(DeferredTask.class))).thenAnswer(inv -> inv.getArgument(0));

        var executed = new AtomicBoolean(false);
        var result = deferredMessageService.executeOrDefer(
                "FIRST_CONTACT", Map.of("key", "value"), () -> executed.set(true));

        assertThat(result).isFalse();
        assertThat(executed).isFalse();

        var captor = ArgumentCaptor.forClass(DeferredTask.class);
        verify(taskRepository).save(captor.capture());
        var saved = captor.getValue();
        assertThat(saved.getTaskType()).isEqualTo("FIRST_CONTACT");
        assertThat(saved.getStatus()).isEqualTo("PENDING");
        assertThat(saved.getPayload()).contains("key");
    }

    @Test
    void deferWithDelay_savesWithCorrectExecuteAt() {
        when(taskRepository.save(any(DeferredTask.class))).thenAnswer(inv -> inv.getArgument(0));

        deferredMessageService.deferWithDelay("FOLLOW_UP", Map.of("conversationId", 1L), 5);

        var captor = ArgumentCaptor.forClass(DeferredTask.class);
        verify(taskRepository).save(captor.capture());
        var saved = captor.getValue();
        assertThat(saved.getTaskType()).isEqualTo("FOLLOW_UP");
        assertThat(saved.getExecuteAt()).isAfter(java.time.Instant.now().plusSeconds(200));
    }
}
