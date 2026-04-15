package com.aicomm.conversation;

import com.aicomm.agent.CommunicationAgentFactory;
import com.aicomm.agent.ConversationContext;
import com.aicomm.agent.tools.ConversationTools;
import com.aicomm.domain.ChannelType;
import com.aicomm.domain.Conversation;
import com.aicomm.domain.Persona;
import com.aicomm.persona.PersonaService;
import com.aicomm.schedule.DeferredMessageService;
import com.aicomm.schedule.DeferredTaskExecutor;
import com.aicomm.telegram.TelegramClientService;
import com.aicomm.util.MaskingUtil;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Handles incoming replies from candidates.
 * Saves incoming message immediately, but defers AI response + send if outside work hours.
 *
 * Runs @Async to not block the TDLib callback thread.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationContinuationService {

    private final ConversationService conversationService;
    private final PersonaService personaService;
    private final CommunicationAgentFactory agentFactory;
    private final ChatModel chatModel;
    private final TelegramClientService telegramClientService;
    private final DeferredMessageService deferredMessageService;
    private final ScheduledFollowUpService scheduledFollowUpService;

    @Async
    public void handleIncomingReply(long senderId, String messageText) {
        var contactId = String.valueOf(senderId);

        var conversationOpt = conversationService.findActiveByContact(ChannelType.TELEGRAM, contactId);
        if (conversationOpt.isEmpty()) {
            return; // Not our contact — ignore silently
        }

        var conversation = conversationOpt.get();
        log.info("Incoming reply for conversationId={}, contactId={}: {}",
                conversation.getId(), MaskingUtil.maskContactId(contactId),
                MaskingUtil.truncate(messageText, 80));

        // ESCALATED — save message but don't generate AI reply (admin handles it)
        if (conversation.getStatus() == com.aicomm.domain.ConversationStatus.ESCALATED) {
            conversationService.addMessage(conversation, "USER", messageText);
            log.info("Conversation id={} is ESCALATED, message saved but AI skipped", conversation.getId());
            return;
        }

        var personaOpt = personaService.getByRef(conversation.getRef());
        if (personaOpt.isEmpty()) {
            log.error("Persona not found for ref={}, conversationId={}", conversation.getRef(), conversation.getId());
            return;
        }

        // Save incoming message immediately (so we don't lose it)
        conversationService.addMessage(conversation, "USER", messageText);

        var persona = personaOpt.get();

        // If this is the first candidate reply (2 messages: ASSISTANT first, USER reply)
        // and persona has a second_message_template — send template only if reply is positive
        long messageCount = conversationService.countMessages(conversation.getId());
        if (messageCount == 2 && persona.getSecondMessageTemplate() != null
                && !persona.getSecondMessageTemplate().isBlank()) {
            if (isPositiveReply(messageText)) {
                deferredMessageService.executeOrDefer(
                        DeferredTaskExecutor.TYPE_REPLY,
                        java.util.Map.of("conversationId", conversation.getId()),
                        () -> sendTemplateReply(conversation, persona.getSecondMessageTemplate())
                );
                return;
            }
            // Negative or unclear reply — let AI handle it naturally
            log.info("First reply for conversationId={} is not positive, routing to AI", conversation.getId());
        }

        // Defer AI response if outside work hours
        var payload = java.util.Map.of("conversationId", conversation.getId());
        deferredMessageService.executeOrDefer(
                DeferredTaskExecutor.TYPE_REPLY,
                payload,
                () -> generateAndSendReply(conversation, persona, messageText)
        );
    }

    private static final String INTEREST_CLASSIFIER_PROMPT = """
            Кандидату написали: "Подскажите, актуален ли для Вас сейчас поиск работы?"
            Кандидат ответил: "%s"

            Кандидат заинтересован в вакансии или хотя бы готов продолжить разговор?
            Ответь ТОЛЬКО одним словом: YES или NO.
            YES — если интерес есть, даже минимальный, или ответ нейтральный/неясный.
            NO — если явный отказ (уже нашёл работу, не интересно, не ищу).
            """;

    /**
     * Lightweight AI classification: is the candidate's first reply positive?
     * Uses a single cheap API call (~$0.001) to understand nuance.
     */
    private boolean isPositiveReply(String text) {
        try {
            var prompt = INTEREST_CLASSIFIER_PROMPT.formatted(text);
            var response = chatModel.chat(dev.langchain4j.data.message.UserMessage.from(prompt));
            var answer = response.aiMessage().text().trim().toUpperCase();
            log.debug("Interest classification for '{}': {}", MaskingUtil.truncate(text, 40), answer);
            return answer.contains("YES");
        } catch (Exception e) {
            log.warn("Interest classification failed, assuming positive: {}", e.getMessage());
            return true;
        }
    }

    /**
     * Sends a hardcoded template message (e.g. second message with experience questions).
     * No AI involved — just save and send.
     */
    private void sendTemplateReply(Conversation conversation, String templateText) {
        try {
            conversationService.addMessage(conversation, "ASSISTANT", templateText);

            telegramClientService.sendMessage(conversation.getContactId(), templateText)
                    .whenComplete((msg, ex) -> {
                        if (ex != null) {
                            log.error("Failed to send template reply to contactId={}: {}",
                                    MaskingUtil.maskContactId(conversation.getContactId()), ex.getMessage());
                        }
                    });

            log.info("Template reply sent for conversationId={}", conversation.getId());
        } catch (Exception e) {
            log.error("Failed to send template reply for conversationId={}: {}",
                    conversation.getId(), e.getMessage(), e);
        }
    }

    /**
     * Fallback: calls ChatModel directly without tools when tool loop is detected.
     * Enriches system prompt with fresh conversation status so the model knows what tools were already executed.
     */
    private String retryWithoutTools(String systemPrompt, String userMessage, long conversationId) {
        try {
            var enrichedPrompt = systemPrompt;
            var freshStatus = conversationService.getStatus(conversationId);
            if (freshStatus == com.aicomm.domain.ConversationStatus.TEST_SENT) {
                enrichedPrompt += "\n\nВАЖНО: тестовое задание ТОЛЬКО ЧТО отправлено кандидату в этом же ответе. "
                        + "Никаких других вопросов.";
            }
            var response = chatModel.chat(
                    SystemMessage.from(enrichedPrompt),
                    UserMessage.from(userMessage)
            );
            return response.aiMessage().text();
        } catch (Exception e) {
            log.error("Plain chat fallback also failed: {}", e.getMessage());
            return "Спасибо за сообщение! Я передам информацию команде и вернусь с ответом.";
        }
    }

    private void generateAndSendReply(Conversation conversation, Persona persona, String messageText) {
        try {
            // Analyze if candidate wants to be contacted later (separate lightweight AI call)
            scheduledFollowUpService.analyzeAndScheduleIfNeeded(messageText, conversation);

            // Enrich system prompt: candidate context first, then vacancy info LAST
            // (LLMs pay most attention to the end of system prompt)
            var systemPrompt = persona.getSystemPrompt();
            if (conversation.getCandidateContext() != null) {
                systemPrompt += "\n\nКонтекст кандидата:\n" + conversation.getCandidateContext();
            }
            var freshStatus = conversationService.getStatus(conversation.getId());
            if (freshStatus != null) {
                systemPrompt += "\n\nТекущий статус диалога: " + freshStatus
                        + ". Если статус TEST_SENT — тестовое задание УЖЕ отправлено, НЕ вызывай sendTestTask повторно.";
            }
            // Vacancy info goes LAST — this is the data AI must use for answering questions
            if (persona.getVacancyInfo() != null && !persona.getVacancyInfo().isBlank()) {
                systemPrompt += "\n\n===== ВАКАНСИЯ (единственный источник правды) =====\n"
                        + persona.getVacancyInfo()
                        + "\n===== КОНЕЦ ДАННЫХ О ВАКАНСИИ =====\n"
                        + "Если ответа на вопрос кандидата НЕТ между этими маркерами — вызови escalateToHuman.";
            }

            log.info("[DIAG] conversationId={}, model={}, vacancyInfo={}, promptLength={}, hasMarker={}",
                    conversation.getId(),
                    "env:" + System.getenv("OPENAI_MODEL"),
                    persona.getVacancyInfo() != null ? persona.getVacancyInfo().length() + " chars" : "NULL",
                    systemPrompt.length(),
                    systemPrompt.contains("===== ВАКАНСИЯ"));

            String aiResponse;
            try {
                ConversationContext.set(conversation);
                ConversationTools.resetCallCount();
                aiResponse = agentFactory.getAgent().chat(conversation.getId(), systemPrompt, messageText);
            } catch (RuntimeException e) {
                if (e.getMessage() != null && e.getMessage().contains("exceeded")) {
                    log.warn("Tool loop for conversationId={}, retrying as plain chat", conversation.getId());
                    aiResponse = retryWithoutTools(systemPrompt, messageText, conversation.getId());
                } else {
                    throw e;
                }
            } finally {
                ConversationContext.clear();
                ConversationTools.clearCallCount();
            }

            log.info("AI response for conversationId={}: {}",
                    conversation.getId(), com.aicomm.util.MaskingUtil.truncate(aiResponse, 80));

            conversationService.addMessage(conversation, "ASSISTANT", aiResponse);

            telegramClientService.sendMessage(
                    conversation.getContactId(), aiResponse
            ).whenComplete((msg, ex) -> {
                if (ex != null) {
                    log.error("Failed to send reply to contactId={}: {}", conversation.getContactId(), ex.getMessage());
                }
            });
        } catch (Exception e) {
            log.error("Failed to generate/send reply for conversationId={}: {}", conversation.getId(), e.getMessage(), e);
        }
    }
}
