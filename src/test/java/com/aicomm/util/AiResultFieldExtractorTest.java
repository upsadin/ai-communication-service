package com.aicomm.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiResultFieldExtractorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String FIELD_MAPPING = """
            {"nameField":"full_name","contactField":"contacts.telegram","reasonField":"reason"}
            """;

    @Test
    void extract_topLevelField() throws Exception {
        var aiResult = MAPPER.readTree("""
                {"full_name":"Viktor","reason":"Java expert"}
                """);
        var extractor = new AiResultFieldExtractor(FIELD_MAPPING);

        assertThat(extractor.extract(aiResult, "nameField")).isEqualTo("Viktor");
        assertThat(extractor.extract(aiResult, "reasonField")).isEqualTo("Java expert");
    }

    @Test
    void extract_nestedField() throws Exception {
        var aiResult = MAPPER.readTree("""
                {"contacts":{"telegram":"vikulitko","email":"test@mail.com"}}
                """);
        var extractor = new AiResultFieldExtractor(FIELD_MAPPING);

        assertThat(extractor.extract(aiResult, "contactField")).isEqualTo("vikulitko");
    }

    @Test
    void extract_returnsNull_whenFieldMissing() throws Exception {
        var aiResult = MAPPER.readTree("""
                {"other_field":"value"}
                """);
        var extractor = new AiResultFieldExtractor(FIELD_MAPPING);

        assertThat(extractor.extract(aiResult, "nameField")).isNull();
    }

    @Test
    void extract_returnsNull_whenMappingKeyMissing() throws Exception {
        var aiResult = MAPPER.readTree("""
                {"full_name":"Viktor"}
                """);
        var extractor = new AiResultFieldExtractor(FIELD_MAPPING);

        assertThat(extractor.extract(aiResult, "unknownKey")).isNull();
    }

    @Test
    void extract_returnsNull_whenAiResultNull() {
        var extractor = new AiResultFieldExtractor(FIELD_MAPPING);

        assertThat(extractor.extract(null, "nameField")).isNull();
    }

    @Test
    void extract_returnsNull_whenNestedPathPartiallyExists() throws Exception {
        var aiResult = MAPPER.readTree("""
                {"contacts":{}}
                """);
        var extractor = new AiResultFieldExtractor(FIELD_MAPPING);

        assertThat(extractor.extract(aiResult, "contactField")).isNull();
    }

    @Test
    void extractByPath_simpleField() throws Exception {
        var node = MAPPER.readTree("""
                {"name":"Anna"}
                """);
        assertThat(AiResultFieldExtractor.extractByPath(node, "name")).isEqualTo("Anna");
    }

    @Test
    void extractByPath_deeplyNested() throws Exception {
        var node = MAPPER.readTree("""
                {"a":{"b":{"c":"deep"}}}
                """);
        assertThat(AiResultFieldExtractor.extractByPath(node, "a.b.c")).isEqualTo("deep");
    }

    @Test
    void constructor_throwsOnInvalidJson() {
        assertThatThrownBy(() -> new AiResultFieldExtractor("not json"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void extract_withRealCandidateJavaSchema() throws Exception {
        var aiResult = MAPPER.readTree("""
                {
                  "matches": true,
                  "confidence": 0.9,
                  "reason": "5 лет опыта в Java и Spring Boot",
                  "full_name": "Viktor Vikulitko",
                  "contacts": {
                    "email": "test@mail.com",
                    "phone": "+7999999",
                    "linkedin": null,
                    "telegram": "vikulitko"
                  }
                }
                """);
        var extractor = new AiResultFieldExtractor(FIELD_MAPPING);

        assertThat(extractor.extract(aiResult, "nameField")).isEqualTo("Viktor Vikulitko");
        assertThat(extractor.extract(aiResult, "contactField")).isEqualTo("vikulitko");
        assertThat(extractor.extract(aiResult, "reasonField")).isEqualTo("5 лет опыта в Java и Spring Boot");
    }

    @Test
    void defaultMapping_usedWhenNull() throws Exception {
        var aiResult = MAPPER.readTree("""
                {"full_name":"Anna","contacts":{"telegram":"anna123"},"reason":"Go expert"}
                """);
        var extractor = new AiResultFieldExtractor(null);

        assertThat(extractor.extract(aiResult, "nameField")).isEqualTo("Anna");
        assertThat(extractor.extract(aiResult, "contactField")).isEqualTo("anna123");
        assertThat(extractor.extract(aiResult, "reasonField")).isEqualTo("Go expert");
    }

    @Test
    void defaultMapping_usedWhenEmpty() throws Exception {
        var aiResult = MAPPER.readTree("""
                {"full_name":"Anna","contacts":{"telegram":"anna123"},"reason":"Go expert"}
                """);
        var extractor = new AiResultFieldExtractor("{}");

        assertThat(extractor.extract(aiResult, "nameField")).isEqualTo("Anna");
        assertThat(extractor.extract(aiResult, "contactField")).isEqualTo("anna123");
    }

    @Test
    void defaultMapping_usedWhenBlank() throws Exception {
        var aiResult = MAPPER.readTree("""
                {"full_name":"Anna","contacts":{"telegram":"anna123"}}
                """);
        var extractor = new AiResultFieldExtractor("   ");

        assertThat(extractor.extract(aiResult, "nameField")).isEqualTo("Anna");
        assertThat(extractor.extract(aiResult, "contactField")).isEqualTo("anna123");
    }
}
