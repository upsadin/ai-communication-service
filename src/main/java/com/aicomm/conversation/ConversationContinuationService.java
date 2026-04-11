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

        var personaOpt = personaService.getByRef(conversation.getRef());
        if (personaOpt.isEmpty()) {
            log.error("Persona not found for ref={}, conversationId={}", conversation.getRef(), conversation.getId());
            return;
        }

        // Save incoming message immediately (so we don't lose it)
        conversationService.addMessage(conversation, "USER", messageText);

        // Defer AI response if outside work hours
        var payload = java.util.Map.of("conversationId", conversation.getId());
        deferredMessageService.executeOrDefer(
                DeferredTaskExecutor.TYPE_REPLY,
                payload,
                () -> generateAndSendReply(conversation, personaOpt.get(), messageText)
        );
    }

    /**
     * Fallback: calls ChatModel directly without tools when tool loop is detected.
     * Gives a contextual AI response instead of a hardcoded string.
     */
    private String retryWithoutTools(String systemPrompt, String userMessage) {
        try {
            var response = chatModel.chat(
                    SystemMessage.from(systemPrompt),
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

            // Enrich system prompt with candidate context + current status
            var systemPrompt = persona.getSystemPrompt();
            if (conversation.getCandidateContext() != null) {
                systemPrompt += "\n\nКонтекст кандидата:\n" + conversation.getCandidateContext();
            }
            // Tell AI the current conversation status so it doesn't re-trigger tools
            var freshStatus = conversationService.getStatus(conversation.getId());
            if (freshStatus != null) {
                systemPrompt += "\n\nТекущий статус диалога: " + freshStatus
                        + ". Если статус TEST_SENT — тестовое задание УЖЕ отправлено, НЕ вызывай sendTestTask повторно.";
            }

            String aiResponse;
            try {
                ConversationContext.set(conversation);
                ConversationTools.resetCallCount();
                aiResponse = agentFactory.getAgent().chat(conversation.getId(), systemPrompt, messageText);
            } catch (RuntimeException e) {
                if (e.getMessage() != null && e.getMessage().contains("exceeded")) {
                    log.warn("Tool loop for conversationId={}, retrying as plain chat", conversation.getId());
                    aiResponse = retryWithoutTools(systemPrompt, messageText);
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
