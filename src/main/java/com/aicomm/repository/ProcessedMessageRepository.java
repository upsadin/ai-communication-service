package com.aicomm.repository;

import com.aicomm.domain.ProcessedMessage;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessedMessageRepository extends JpaRepository<ProcessedMessage, Long> {

    boolean existsBySourceId(String sourceId);
}
