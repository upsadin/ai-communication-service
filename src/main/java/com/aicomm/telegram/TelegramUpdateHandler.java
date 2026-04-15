package com.aicomm.telegram;

import com.aicomm.conversation.ConversationContinuationService;
import com.aicomm.conversation.ConversationService;
import com.aicomm.domain.ChannelType;
import com.aicomm.domain.ConversationStatus;
import it.tdlight.jni.TdApi;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * Listens for incoming Telegram messages via TDLib callback.
 * Extracts text representation from any message type and delegates
 * to ConversationContinuationService for AI processing.
 *
 * Dependencies are @Lazy to break the circular chain:
 * TelegramAuthManager → this → ConversationContinuationService → TelegramClientService → TelegramAuthManager
 */
@Slf4j
@Component
public class TelegramUpdateHandler {

    private final ConversationContinuationService continuationService;
    private final ConversationService conversationService;
    private final TelegramClientService telegramClientService;

    public TelegramUpdateHandler(@Lazy ConversationContinuationService continuationService,
                                 @Lazy ConversationService conversationService,
                                 @Lazy TelegramClientService telegramClientService) {
        this.continuationService = continuationService;
        this.conversationService = conversationService;
        this.telegramClientService = telegramClientService;
    }

    /**
     * Called by TDLib (registered in TelegramAuthManager) when a new message arrives.
     */
    public void onUpdateNewMessage(TdApi.UpdateNewMessage update) {
        var message = update.message;

        // Save outgoing admin messages for ESCALATED conversations (so AI has context when resumed)
        if (message.isOutgoing) {
            handleOutgoingMessage(message);
            return;
        }

        var senderId = extractSenderId(message.senderId);
        var contentDescription = extractContentDescription(message.content);

        if (contentDescription == null) {
            log.debug("Unsupported message type from senderId={}: {}",
                    senderId, message.content.getClass().getSimpleName());
            return;
        }

        telegramClientService.markAsRead(message.chatId, message.id);

        continuationService.handleIncomingReply(senderId, contentDescription.text());
    }

    /**
     * Saves outgoing messages (sent by admin from the Telegram account) to conversation history,
     * but only for ESCALATED conversations. This way AI has full context when resumed.
     */
    private void handleOutgoingMessage(TdApi.Message message) {
        var chatId = String.valueOf(message.chatId);
        var conversationOpt = conversationService.findActiveByContact(ChannelType.TELEGRAM, chatId);
        if (conversationOpt.isEmpty()) return;

        var conversation = conversationOpt.get();
        if (conversation.getStatus() != ConversationStatus.ESCALATED) return;

        var content = extractContentDescription(message.content);
        if (content == null) return;

        conversationService.addMessage(conversation, "ASSISTANT", content.text());
        log.info("Admin outgoing message saved for ESCALATED conversationId={}", conversation.getId());
    }

    private ContentDescription extractContentDescription(TdApi.MessageContent content) {
        return switch (content) {
            case TdApi.MessageText text ->
                    new ContentDescription("text", text.text.text);

            case TdApi.MessagePhoto photo ->
                    new ContentDescription("photo",
                            photo.caption.text.isEmpty()
                                    ? "[Фото без подписи]"
                                    : "[Фото] " + photo.caption.text);

            case TdApi.MessageSticker sticker ->
                    new ContentDescription("sticker",
                            "[Стикер: " + sticker.sticker.emoji + "]");

            case TdApi.MessageDocument document ->
                    new ContentDescription("document",
                            "[Документ: " + document.document.fileName + "]"
                                    + (document.caption.text.isEmpty() ? "" : " " + document.caption.text));

            case TdApi.MessageVoiceNote voice ->
                    new ContentDescription("voice",
                            "[Голосовое сообщение]"
                                    + (voice.caption.text.isEmpty() ? "" : " " + voice.caption.text));

            case TdApi.MessageVideoNote ignored ->
                    new ContentDescription("video_note", "[Видео-кружок]");

            case TdApi.MessageVideo video ->
                    new ContentDescription("video",
                            "[Видео]"
                                    + (video.caption.text.isEmpty() ? "" : " " + video.caption.text));

            case TdApi.MessageAnimation animation ->
                    new ContentDescription("animation",
                            "[GIF]"
                                    + (animation.caption.text.isEmpty() ? "" : " " + animation.caption.text));

            case TdApi.MessageAnimatedEmoji animatedEmoji ->
                    new ContentDescription("emoji", animatedEmoji.emoji);

            case TdApi.MessageLocation ignored ->
                    new ContentDescription("location", "[Геолокация]");

            case TdApi.MessageContact contact ->
                    new ContentDescription("contact",
                            "[Контакт: " + contact.contact.firstName + " " + contact.contact.lastName + "]");

            case TdApi.MessagePoll poll ->
                    new ContentDescription("poll", "[Опрос: " + poll.poll.question.text + "]");

            default -> null;
        };
    }

    private long extractSenderId(TdApi.MessageSender sender) {
        return switch (sender) {
            case TdApi.MessageSenderUser user -> user.userId;
            case TdApi.MessageSenderChat chat -> chat.chatId;
            default -> 0L;
        };
    }

    public record ContentDescription(String type, String text) {}
}
