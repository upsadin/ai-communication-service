package com.aicomm.agent.tools;

import com.aicomm.agent.ConversationContext;
import com.aicomm.conversation.ConversationService;
import com.aicomm.domain.ConversationStatus;
import com.aicomm.persona.PersonaService;
import com.aicomm.schedule.DeferredMessageService;
import com.aicomm.schedule.DeferredTaskExecutor;
import com.aicomm.telegram.TelegramClientService;
import com.aicomm.util.MaskingUtil;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * LangChain4j @Tool methods available to the AI agent.
 * AI calls these automatically based on conversation context and system prompt instructions.
 * Current conversation is obtained from ConversationContext (ThreadLocal).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConversationTools {

    private static final int MAX_TOOL_CALLS_PER_CHAT = 3;
    private static final ThreadLocal<Integer> TOOL_CALL_COUNT = ThreadLocal.withInitial(() -> 0);

    private final ConversationService conversationService;
    private final PersonaService personaService;
    private final TelegramClientService telegramClientService;
    private final DeferredMessageService deferredMessageService;

    @Value("${app.test-task.timeout-days:3}")
    private int testTaskTimeoutDays;

    /** Call from ConversationContext.set() area to reset counter before each agent.chat() */
    public static void resetCallCount() {
        TOOL_CALL_COUNT.set(0);
    }

    public static void clearCallCount() {
        TOOL_CALL_COUNT.remove();
    }

    private boolean checkAndIncrementCallCount(String toolName) {
        int count = TOOL_CALL_COUNT.get();
        if (count >= MAX_TOOL_CALLS_PER_CHAT) {
            log.warn("Tool {} call limit reached ({}/{}), skipping", toolName, count, MAX_TOOL_CALLS_PER_CHAT);
            return false;
        }
        TOOL_CALL_COUNT.set(count + 1);
        return true;
    }

    @Tool("Отправь тестовое задание кандидату. Вызови ОДИН РАЗ когда кандидат подтвердил готовность к тестовому.")
    public String sendTestTask() {
        if (!checkAndIncrementCallCount("sendTestTask")) return "Уже выполнено";
        var conversation = ConversationContext.get();
        if (conversation == null) {
            log.error("sendTestTask called without ConversationContext");
            return "Ошибка: контекст диалога не найден";
        }

        // Guard: don't send test task twice (check fresh status from DB)
        var freshStatus = conversationService.getStatus(conversation.getId());
        if (freshStatus == ConversationStatus.TEST_SENT || freshStatus == ConversationStatus.COMPLETED) {
            return "Тестовое задание уже было отправлено ранее";
        }

        var personaOpt = personaService.getByRef(conversation.getRef());
        if (personaOpt.isEmpty() || personaOpt.get().getTestTaskUrl() == null) {
            return "Тестовое задание не настроено для этой вакансии";
        }

        try {
            var taskUrl = personaOpt.get().getTestTaskUrl();
            telegramClientService.sendMessage(conversation.getContactId(),
                    "Вот ссылка на тестовое задание: " + taskUrl
                            + "\nНа выполнение даётся " + testTaskTimeoutDays + " дня. Удачи!").join();

            conversationService.updateStatus(conversation.getId(), ConversationStatus.TEST_SENT);

            long timeoutMinutes = (long) testTaskTimeoutDays * 24 * 60;
            deferredMessageService.deferWithDelay(
                    DeferredTaskExecutor.TYPE_TEST_TIMEOUT,
                    Map.of("conversationId", conversation.getId()),
                    timeoutMinutes);

            log.info("Test task sent for conversationId={}, timeout in {} days",
                    conversation.getId(), testTaskTimeoutDays);

            // Notify team automatically — internal call, does not consume a tool slot
            notifyTeamInternal("Кандидату отправлено тестовое задание. Ожидается выполнение в течение "
                    + testTaskTimeoutDays + " дней.");

            return "Тестовое задание отправлено кандидату. Напиши ему ТОЛЬКО: «Отлично, ссылка уже у Вас. Если будут вопросы по заданию — пишите!» — и ничего больше. НЕ задавай вопрос о готовности повторно.";
        } catch (Exception e) {
            log.error("sendTestTask failed for conversationId={}: {}", conversation.getId(), e.getMessage(), e);
            return "Не удалось отправить тестовое задание из-за технической проблемы. Сообщи кандидату, что задание будет отправлено чуть позже.";
        }
    }

    @Tool("Уведоми команду. Вызови ОДИН РАЗ когда кандидат сдал тестовое или произошло что-то важное.")
    public String notifyTeam(@P("message") String message) {
        if (!checkAndIncrementCallCount("notifyTeam")) return "Уже уведомлено";
        var conversation = ConversationContext.get();
        if (conversation == null) {
            log.error("notifyTeam called without ConversationContext");
            return "Ошибка: контекст диалога не найден";
        }
        try {
            notifyTeamInternal(message);
            return "Команда уведомлена";
        } catch (Exception e) {
            log.error("notifyTeam failed for conversationId={}: {}", conversation.getId(), e.getMessage(), e);
            return "Не удалось уведомить команду из-за технической проблемы. Продолжай диалог с кандидатом.";
        }
    }

    @Tool("Заверши диалог. Вызови ОДИН РАЗ когда кандидат отказался или общение завершено.")
    public String closeConversation(@P("reason") String reason) {
        if (!checkAndIncrementCallCount("closeConversation")) return "Уже завершено";
        var conversation = ConversationContext.get();
        if (conversation == null) {
            log.error("closeConversation called without ConversationContext");
            return "Ошибка: контекст диалога не найден";
        }

        try {
            conversationService.updateStatus(conversation.getId(), ConversationStatus.COMPLETED);
            // Internal call — does not consume a tool slot
            notifyTeamInternal("Диалог завершён. Причина: " + reason);
            log.info("Conversation id={} closed: {}", conversation.getId(), reason);
            return "Диалог завершён";
        } catch (Exception e) {
            log.error("closeConversation failed for conversationId={}: {}", conversation.getId(), e.getMessage(), e);
            return "Не удалось завершить диалог из-за технической проблемы. Попрощайся с кандидатом вежливо.";
        }
    }

    @Tool("Передай диалог администратору. Вызови если не знаешь что ответить или ситуация требует вмешательства человека.")
    public String escalateToHuman(@P("reason") String reason) {
        if (!checkAndIncrementCallCount("escalateToHuman")) return "Уже передано";
        var conversation = ConversationContext.get();
        if (conversation == null) {
            log.error("escalateToHuman called without ConversationContext");
            return "Ошибка: контекст диалога не найден";
        }

        try {
            conversationService.updateStatus(conversation.getId(), ConversationStatus.ESCALATED);

            var fullName = conversation.getFullName() != null ? conversation.getFullName() : "Unknown";
            var contactLink = buildContactLink(conversation);
            var escalationMessage = "⚠ Нужна помощь!\nДиалог #%d (%s)\nКандидат: %s (%s)\nПричина: %s\nResume: PATCH /internal/admin/conversations/%d/resume"
                    .formatted(conversation.getId(), conversation.getRef(),
                            fullName, contactLink, reason, conversation.getId());
            notifyTeamInternal(escalationMessage);

            log.info("Conversation id={} escalated to human: {}", conversation.getId(), reason);
            return "Диалог передан администратору. Напиши кандидату: «Хороший вопрос! Уточню информацию у коллег и вернусь с ответом в ближайшее время.»";
        } catch (Exception e) {
            log.error("escalateToHuman failed for conversationId={}: {}", conversation.getId(), e.getMessage(), e);
            return "Не удалось передать диалог. Попробуй ответить кандидату самостоятельно.";
        }
    }

    /**
     * Sends team notification without consuming an AI tool slot.
     * Used internally by sendTestTask and closeConversation, and as the delegate from @Tool notifyTeam.
     * Includes candidate's full name and contact ID so the team has full context.
     */
    private void notifyTeamInternal(String message) {
        var conversation = ConversationContext.get();
        if (conversation == null) return;

        var personaOpt = personaService.getByRef(conversation.getRef());
        if (personaOpt.isEmpty() || personaOpt.get().getNotificationContact() == null) {
            log.warn("Notification contact not configured for ref={}", conversation.getRef());
            return;
        }

        var contact = personaOpt.get().getNotificationContact();
        var fullName = conversation.getFullName() != null ? conversation.getFullName() : "Unknown";
        var contactLink = buildContactLink(conversation);
        var notificationText = "[%s] %s (%s)\n%s".formatted(
                conversation.getRef(), fullName, contactLink, message);

        if (contact.startsWith("@")) {
            telegramClientService.sendMessageByUsername(contact, notificationText);
        } else {
            telegramClientService.sendMessageByChatId(Long.parseLong(contact), notificationText);
        }

        log.info("Team notified for conversationId={}: {}", conversation.getId(),
                MaskingUtil.truncate(message, 80));
    }

    /**
     * Builds a clickable Telegram link for the candidate.
     * Prefers @username (t.me/username), falls back to tg://user?id= for numeric IDs.
     */
    private String buildContactLink(com.aicomm.domain.Conversation conversation) {
        var original = conversation.getOriginalContactId();

        // If original contact was a username (not phone, not numeric)
        if (original != null && !original.isBlank()
                && !original.startsWith("+") && !original.matches("\\d+")) {
            var username = original.startsWith("@") ? original.substring(1) : original;
            return "@" + username + " (t.me/" + username + ")";
        }

        // If original was a phone number
        if (original != null && original.startsWith("+")) {
            return original;
        }

        // Fallback: numeric chatId
        return "tg://user?id=" + conversation.getContactId();
    }
}
