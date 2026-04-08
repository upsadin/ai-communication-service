package com.aicomm.idempotency;

import com.aicomm.domain.ProcessedMessage;
import com.aicomm.repository.ProcessedMessageRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IdempotencyServiceTest {

    @Mock
    private ProcessedMessageRepository repository;

    @InjectMocks
    private IdempotencyService idempotencyService;

    @Test
    void isAlreadyProcessed_returnsTrue_whenExists() {
        when(repository.existsBySourceId("src-1")).thenReturn(true);

        assertThat(idempotencyService.isAlreadyProcessed("src-1")).isTrue();
    }

    @Test
    void isAlreadyProcessed_returnsFalse_whenNotExists() {
        when(repository.existsBySourceId("src-1")).thenReturn(false);

        assertThat(idempotencyService.isAlreadyProcessed("src-1")).isFalse();
    }

    @Test
    void markProcessed_savesAndReturnsTrue() {
        when(repository.save(any(ProcessedMessage.class))).thenReturn(new ProcessedMessage("src-1"));

        assertThat(idempotencyService.markProcessed("src-1")).isTrue();
        verify(repository).save(any(ProcessedMessage.class));
    }

    @Test
    void markProcessed_returnsFalse_onDuplicateConstraint() {
        when(repository.save(any())).thenThrow(new DataIntegrityViolationException("duplicate"));

        assertThat(idempotencyService.markProcessed("src-1")).isFalse();
    }
}
