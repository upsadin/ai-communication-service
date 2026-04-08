package com.aicomm.kafka.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Incoming Kafka message representing a task to process.
 * Sent by the upstream filtering service after AI analysis.
 *
 * aiResult is kept as raw JsonNode because its structure depends on
 * the schemaJson configured per ref in the upstream service's PromptEntity.
 * Each ref may produce a different set of fields.
 *
 * @param ref      persona reference (e.g. "candidate_java")
 * @param sourceId unique message identifier for idempotency
 * @param aiResult raw AI analysis result — structure varies per ref
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MessageProcessingTask(
        String ref,
        String sourceId,
        JsonNode aiResult
) {}
