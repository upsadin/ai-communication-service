package com.aicomm.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

/**
 * Extracts fields from aiResult JSON using paths defined in Persona's field_mapping.
 *
 * field_mapping example: {"nameField":"full_name","contactField":"contacts.telegram","reasonField":"reason"}
 * Supports dot-notation for nested paths: "contacts.telegram" → root.contacts.telegram
 */
@Slf4j
public class AiResultFieldExtractor {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JsonNode mappingNode;

    public AiResultFieldExtractor(String fieldMappingJson) {
        try {
            this.mappingNode = MAPPER.readTree(fieldMappingJson);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid field_mapping JSON: " + fieldMappingJson, e);
        }
    }

    /**
     * Extracts a value from aiResult using the path configured for the given mapping key.
     *
     * @param aiResult    raw JSON from upstream service
     * @param mappingKey  key in field_mapping (e.g. "nameField", "contactField", "reasonField")
     * @return extracted string value, or null if path not found or not configured
     */
    public String extract(JsonNode aiResult, String mappingKey) {
        if (aiResult == null || mappingNode == null) return null;

        var pathNode = mappingNode.get(mappingKey);
        if (pathNode == null || pathNode.isNull()) return null;

        return extractByPath(aiResult, pathNode.asText());
    }

    /**
     * Traverses JSON by dot-separated path.
     * "contacts.telegram" → root.get("contacts").get("telegram")
     */
    public static String extractByPath(JsonNode root, String path) {
        if (root == null || path == null || path.isBlank()) return null;

        var current = root;
        for (var segment : path.split("\\.")) {
            current = current.get(segment);
            if (current == null || current.isNull()) return null;
        }

        return current.asText(null);
    }
}
