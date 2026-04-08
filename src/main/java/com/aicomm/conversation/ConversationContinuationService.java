package com.aicomm.conversation;

import com.aicomm.agent.CommunicationAgentFactory;
import com.aicomm.domain.ChannelType;
import com.aicomm.domain.Conversation;
import com.aicomm.domain.Persona;
import com.aicomm.persona.PersonaService;
import com.aicomm.schedule.DeferredMessageService;
import com.aicomm.schedule.DeferredTaskExecutor;
import com.aicomm.telegram.TelegramClientService;
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
    private final TelegramClientService telegramClientService;
    private final DeferredMessageService deferredMessageService;
    private final ScheduledFollowUpService scheduledFollowUpService;

    @Async
    public void handleIncomingReply(long senderId, String messageText) {
        var contactId = String.valueOf(senderId);

        var conversationOpt = conversationService.findActiveByContact(ChannelType.TELEGRAM, contactId);
        if (conversationOpt.isEmpty()) {
            log.debug("No active conversation for contactId={}, ignoring", contactId);
            return;
        }

        var conversation = conversationOpt.get();
        log.info("Incoming reply for conversationId={}, contactId={}", conversation.getId(), contactId);

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

    private void generateAndSendReply(Conversation conversation, Persona persona, String messageText) {
        try {
            // Analyze if candidate wants to be contacted later (separate lightweight AI call)
            scheduledFollowUpService.analyzeAndScheduleIfNeeded(messageText, conversation);

            // Enrich system prompt with candidate context (never falls out of memory window)
            var systemPrompt = persona.getSystemPrompt();
            if (conversation.getCandidateContext() != null) {
                systemPrompt += "\n\nКонтекст кандидата:\n" + conversation.getCandidateContext();
            }

            var agent = agentFactory.getAgent();
            var aiResponse = agent.chat(conversation.getId(), systemPrompt, messageText);

            log.info("AI response for conversationId={}: {}",
                    conversation.getId(), com.aicomm.util.MaskingUtil.truncate(aiResponse, 80));

            conversationService.addMessage(conversation, "ASSISTANT", aiResponse);

            telegramClientService.sendMessageByChatId(
                    Long.parseLong(conversation.getContactId()), aiResponse
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
