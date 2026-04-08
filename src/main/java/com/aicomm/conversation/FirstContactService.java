package com.aicomm.conversation;

import java.util.Iterator;
import java.util.Map;

import com.aicomm.agent.CommunicationAgentFactory;
import com.aicomm.domain.ChannelType;
import com.aicomm.domain.ConversationStatus;
import com.aicomm.domain.Persona;
import com.aicomm.kafka.dto.MessageProcessingTask;
import com.aicomm.persona.PersonaService;
import com.aicomm.telegram.TelegramClientService;
import com.aicomm.util.AiResultFieldExtractor;
import com.aicomm.util.MaskingUtil;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Handles the first outreach to a candidate:
 * - Extracts contact info from aiResult using Persona's field_mapping
 * - Creates conversation with candidate context
 * - Renders first_message_template with field_mapping + aiResult fields
 * - Generates the first message via AI and sends via Telegram
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FirstContactService {

    private final PersonaService personaService;
    private final ConversationService conversationService;
    private final CommunicationAgentFactory agentFactory;
    private final TelegramClientService telegramClientService;

    public void initiateContact(MessageProcessingTask task) {
        var aiResult = task.aiResult();

        // 1. Lookup persona
        var persona = personaService.getByRef(task.ref())
                .orElseThrow(() -> new IllegalArgumentException("Persona not found for ref=" + task.ref()));

        // 2. Extract contact info via field_mapping
        var extractor = new AiResultFieldExtractor(persona.getFieldMapping());
        var contactId = extractor.extract(aiResult, "contactField");
        var fullName = extractor.extract(aiResult, "nameField");

        // 3. Save candidate context and create conversation
        var candidateContext = aiResult != null ? aiResult.toPrettyString() : "";
        var conversation = conversationService.createConversation(
                task.sourceId(), task.ref(), fullName, ChannelType.TELEGRAM, contactId, candidateContext);

        // 4. Render first message template using field_mapping
        var firstMessagePrompt = renderTemplate(persona, aiResult, extractor);

        // 5. Build enriched system prompt (candidate context never falls out of memory)
        var enrichedSystemPrompt = persona.getSystemPrompt()
                + "\n\nКонтекст кандидата:\n" + candidateContext;

        // 6. Save prompt as USER message (for ChatMemory)
        conversationService.addMessage(conversation, "USER", firstMessagePrompt);

        // 7. Generate first message via AI
        var agent = agentFactory.getAgent();
        var aiResponse = agent.chat(conversation.getId(), enrichedSystemPrompt, firstMessagePrompt);

        log.info("AI first message for conversationId={}: {}",
                conversation.getId(), MaskingUtil.truncate(aiResponse, 80));

        // 8. Save AI response
        conversationService.addMessage(conversation, "ASSISTANT", aiResponse);

        // 9. Send via Telegram with typing simulation
        telegramClientService.sendMessageByChatId(Long.parseLong(contactId), aiResponse)
                .thenRun(() -> conversationService.updateStatus(conversation, ConversationStatus.ACTIVE))
                .exceptionally(ex -> {
                    log.error("Failed to send to contactId={}: {}", contactId, ex.getMessage());
                    conversationService.updateStatus(conversation, ConversationStatus.FAILED);
                    return null;
                })
                .join();
    }

    /**
     * Renders first_message_template by replacing {{placeholders}}.
     * {{name}} and {{reason}} are resolved via field_mapping.
     * Any other {{field}} is resolved directly from aiResult top-level fields.
     */
    private String renderTemplate(Persona persona, JsonNode aiResult, AiResultFieldExtractor extractor) {
        var template = persona.getFirstMessageTemplate();

        // Replace mapped placeholders
        var name = extractor.extract(aiResult, "nameField");
        template = template.replace("{{name}}", name != null ? name : "кандидат");

        var reason = extractor.extract(aiResult, "reasonField");
        template = template.replace("{{reason}}", reason != null ? reason : "");

        // Replace any other {{field}} directly from aiResult top-level
        if (aiResult != null && aiResult.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = aiResult.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                var placeholder = "{{" + entry.getKey() + "}}";
                if (template.contains(placeholder)) {
                    template = template.replace(placeholder, entry.getValue().asText(""));
                }
            }
        }

        return template;
    }
}
