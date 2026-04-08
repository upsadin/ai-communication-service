package com.aicomm.idempotency;

import com.aicomm.domain.ProcessedMessage;
import com.aicomm.repository.ProcessedMessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Guarantees that a sourceId is processed at most once.
 *
 * Two-layer protection:
 *   1. existsBySourceId() — fast path, skips duplicates without INSERT
 *   2. UNIQUE constraint on source_id — final guard against race conditions
 *
 * Both methods run within the caller's @Transactional context.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IdempotencyService {

    private final ProcessedMessageRepository repository;

    /**
     * Fast check — has this sourceId already been processed?
     */
    public boolean isAlreadyProcessed(String sourceId) {
        return repository.existsBySourceId(sourceId);
    }

    /**
     * Mark sourceId as processed. Call AFTER successful business logic.
     *
     * @return true if marked successfully, false if already existed (race condition caught by UNIQUE)
     */
    public boolean markProcessed(String sourceId) {
        try {
            repository.save(new ProcessedMessage(sourceId));
            log.debug("Marked sourceId={} as processed", sourceId);
            return true;
        } catch (DataIntegrityViolationException e) {
            log.warn("Duplicate sourceId={} caught by UNIQUE constraint (race condition)", sourceId);
            return false;
        }
    }
}
