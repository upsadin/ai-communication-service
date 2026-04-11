package com.aicomm.conversation;

import com.aicomm.agent.CommunicationAgentFactory;
import com.aicomm.agent.ConversationContext;
import com.aicomm.agent.tools.ConversationTools;
import com.aicomm.domain.Conversation;
import com.aicomm.persona.PersonaService;
import com.aicomm.repository.ConversationRepository;
import com.aicomm.schedule.DeferredMessageService;
import com.aicomm.schedule.DeferredTaskExecutor;
import com.aicomm.telegram.TelegramClientService;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * Handles "напиши позже" requests from candidates.
 *
 * Uses a separate lightweight AI call to detect the intent,
 * then persists a FOLLOW_UP task in deferred_tasks table.
 * DeferredTaskExecutor picks it up when the time comes.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScheduledFollowUpService {

    private static final Pattern NUMBER_PATTERN = Pattern.compile("\\d+");

    private static final String CLASSIFIER_PROMPT = """
            Проанализируй сообщение кандидата. Просит ли он написать ему позже, через какое-то время, \
            завтра, или говорит что сейчас занят и хочет продолжить потом?
            Если да — ответь ТОЛЬКО числом минут (например: 5, 30, 60, 1440 для завтра).
            Если нет — ответь ТОЛЬКО: 0
            Никакого другого текста, только число.

            Сообщение кандидата: "%s"
            """;

    private static final String FOLLOW_UP_PROMPT =
            "Ты ранее договорилась с кандидатом написать позже. Время пришло. " +
            "Напиши короткое ненавязчивое сообщение — напомни о себе и спроси, удобно ли сейчас пообщаться.";

    private final ChatModel chatModel;
    private final ConversationService conversationService;
    private final ConversationRepository conversationRepository;
    private final PersonaService personaService;
    private final CommunicationAgentFactory agentFactory;
    private final TelegramClientService telegramClientService;
    private final DeferredMessageService deferredMessageService;

    /**
     * Analyzes the candidate's message for a "contact me later" intent.
     * If detected, persists a FOLLOW_UP task in deferred_tasks.
     */
    public void analyzeAndScheduleIfNeeded(String candidateMessage, Conversation conversation) {
        try {
            int delayMinutes = classifyDelay(candidateMessage);

            if (delayMinutes > 0) {
                log.info("Detected 'contact later' for conversationId={}, deferring follow-up in {} min",
                        conversation.getId(), delayMinutes);

                deferredMessageService.deferWithDelay(
                        DeferredTaskExecutor.TYPE_FOLLOW_UP,
                        Map.of("conversationId", conversation.getId()),
                        delayMinutes
                );
            }
        } catch (Exception e) {
            log.debug("Schedule classification failed for conversationId={}: {}",
                    conversation.getId(), e.getMessage());
        }
    }

    /**
     * Called by DeferredTaskExecutor when a FOLLOW_UP task is due.
     */
    public void sendFollowUpForConversation(Long conversationId) {
        var conversationOpt = conversationRepository.findById(conversationId);
        if (conversationOpt.isEmpty()) {
            log.error("Follow-up: conversation not found id={}", conversationId);
            return;
        }

        var conversation = conversationOpt.get();
        var personaOpt = personaService.getByRef(conversation.getRef());
        if (personaOpt.isEmpty()) {
            log.error("Follow-up: persona not found for ref={}", conversation.getRef());
            return;
        }
        var persona = personaOpt.get();

        conversationService.addMessage(conversation, "USER", FOLLOW_UP_PROMPT);

        var systemPrompt = persona.getSystemPrompt();
        if (conversation.getCandidateContext() != null) {
            systemPrompt += "\n\nКонтекст кандидата:\n" + conversation.getCandidateContext();
        }

        String aiResponse;
        try {
            ConversationContext.set(conversation);
            ConversationTools.resetCallCount();
            aiResponse = agentFactory.getAgent().chat(conversation.getId(), systemPrompt, FOLLOW_UP_PROMPT);
        } finally {
            ConversationContext.clear();
            ConversationTools.clearCallCount();
        }

        log.info("Follow-up for conversationId={}: {}",
                conversation.getId(), com.aicomm.util.MaskingUtil.truncate(aiResponse, 80));

        conversationService.addMessage(conversation, "ASSISTANT", aiResponse);

        telegramClientService.sendMessage(
                conversation.getContactId(), aiResponse
        ).whenComplete((msg, ex) -> {
            if (ex != null) {
                log.error("Follow-up send failed for conversationId={}: {}",
                        conversation.getId(), ex.getMessage());
            }
        });
    }

    private int classifyDelay(String candidateMessage) {
        var prompt = CLASSIFIER_PROMPT.formatted(candidateMessage);
        var response = chatModel.chat(UserMessage.from(prompt));
        var text = response.aiMessage().text().trim();

        var matcher = NUMBER_PATTERN.matcher(text);
        if (matcher.find()) {
            return Integer.parseInt(matcher.group());
        }
        return 0;
    }
}
