package com.aicomm.telegram;

import com.aicomm.util.MaskingUtil;
import it.tdlight.jni.TdApi;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Business-level Telegram message sending with human-like behavior:
 * - Sends "typing..." indicator before each message
 * - Delays based on message length (simulates reading + typing)
 * - Adds random jitter to avoid robotic timing
 *
 * Does NOT know about Kafka, LangChain4j, or any other module (loose coupling).
 */
@Slf4j
@Service
public class TelegramClientService {

    private static final int MAX_RETRIES = 3;
    private static final long BASE_RETRY_DELAY_MS = 2000;

    private final TelegramAuthManager authManager;

    /** Milliseconds per character for typing simulation */
    private final int msPerChar;

    /** Minimum typing delay (ms) */
    private final int minTypingMs;

    /** Maximum typing delay (ms) */
    private final int maxTypingMs;

    /** Minimum "reading/thinking" delay before typing starts (ms) */
    private final int minReadDelayMs;

    /** Maximum "reading/thinking" delay before typing starts (ms) */
    private final int maxReadDelayMs;

    public TelegramClientService(
            TelegramAuthManager authManager,
            @Value("${app.telegram.typing.ms-per-char:120}") int msPerChar,
            @Value("${app.telegram.typing.min-typing-ms:3000}") int minTypingMs,
            @Value("${app.telegram.typing.max-typing-ms:55000}") int maxTypingMs,
            @Value("${app.telegram.read-delay.min-ms:3000}") int minReadDelayMs,
            @Value("${app.telegram.read-delay.max-ms:15000}") int maxReadDelayMs) {
        this.authManager = authManager;
        this.msPerChar = msPerChar;
        this.minTypingMs = minTypingMs;
        this.maxTypingMs = maxTypingMs;
        this.minReadDelayMs = minReadDelayMs;
        this.maxReadDelayMs = maxReadDelayMs;
    }

    /**
     * Send a text message to a user by their @username.
     * Shows typing indicator and delays based on message length.
     */
    public CompletableFuture<TdApi.Message> sendMessageByUsername(String username, String text) {
        if (!authManager.isReady()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Telegram client not ready. Current state: " + authManager.getAuthState().get()));
        }

        var cleanUsername = username.startsWith("@") ? username.substring(1) : username;

        return searchPublicChat(cleanUsername)
                .thenCompose(chat -> sendWithTyping(chat.id, text));
    }

    /**
     * Marks a message as read (blue checkmarks for the sender).
     */
    public void markAsRead(long chatId, long messageId) {
        if (!authManager.isReady()) return;
        var request = new TdApi.ViewMessages(chatId, new long[]{messageId}, null, true);
        authManager.getClient().send(request, result -> {
            if (result.isError()) {
                log.debug("Failed to mark as read in chatId={}: {}", MaskingUtil.maskChatId(chatId), result.getError().message);
            }
        });
    }

    /**
     * Send a text message to a known chat by chatId.
     * Shows typing indicator and delays based on message length.
     */
    public CompletableFuture<TdApi.Message> sendMessageByChatId(long chatId, String text) {
        if (!authManager.isReady()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Telegram client not ready. Current state: " + authManager.getAuthState().get()));
        }

        return sendWithTyping(chatId, text);
    }

    /**
     * Send a text message to a contact — auto-detects format:
     * - "+7..." / digits-only starting with country code → phone number (ImportContacts)
     * - "@username" or non-numeric → username (SearchPublicChat)
     * - pure numeric (no +) → chatId
     */
    public CompletableFuture<TdApi.Message> sendMessage(String contactId, String text) {
        if (contactId.startsWith("+")) {
            return sendMessageByPhone(contactId, text);
        }
        try {
            long chatId = Long.parseLong(contactId);
            return sendMessageByChatId(chatId, text);
        } catch (NumberFormatException e) {
            return sendMessageByUsername(contactId, text);
        }
    }

    /**
     * Sends a message to a user by phone number.
     * Flow: ImportContacts → get userId → CreatePrivateChat → sendWithTyping
     */
    private CompletableFuture<TdApi.Message> sendMessageByPhone(String phone, String text) {
        if (!authManager.isReady()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Telegram client not ready"));
        }

        var contact = new TdApi.Contact(phone, "", "", "", 0);
        var importRequest = new TdApi.ImportContacts(new TdApi.Contact[]{contact});
        var future = new CompletableFuture<TdApi.Message>();

        authManager.getClient().send(importRequest, importResult -> {
            if (importResult.isError()) {
                future.completeExceptionally(new TelegramException(
                        "ImportContacts failed: " + importResult.getError().message,
                        importResult.getError().code));
                return;
            }
            var imported = importResult.get();
            if (imported.userIds == null || imported.userIds.length == 0 || imported.userIds[0] == 0) {
                future.completeExceptionally(new TelegramException(
                        "Phone not found in Telegram: " + MaskingUtil.maskPhone(phone), 400));
                return;
            }
            long userId = imported.userIds[0];
            log.debug("Resolved phone {} -> userId={}", MaskingUtil.maskPhone(phone), userId);

            var chatRequest = new TdApi.CreatePrivateChat(userId, true);
            authManager.getClient().send(chatRequest, chatResult -> {
                if (chatResult.isError()) {
                    future.completeExceptionally(new TelegramException(
                            "CreatePrivateChat failed: " + chatResult.getError().message,
                            chatResult.getError().code));
                    return;
                }
                sendWithTyping(chatResult.get().id, text)
                        .whenComplete((msg, ex) -> {
                            if (ex != null) future.completeExceptionally(ex);
                            else future.complete(msg);
                        });
            });
        });

        return future;
    }

    /**
     * Simulates human behavior: read delay → typing indicator → send message.
     *
     * 1. "Reading" pause — HR sees the message, finishes what they're doing (no indicator shown)
     * 2. "Typing" indicator — appears in Telegram, refreshed every 4s
     * 3. Send message
     */
    private CompletableFuture<TdApi.Message> sendWithTyping(long chatId, String text) {
        int readDelayMs = ThreadLocalRandom.current().nextInt(minReadDelayMs, maxReadDelayMs + 1);
        int typingDelayMs = calculateTypingDelay(text);
        log.debug("Human simulation for chatId={}: read={}ms, typing={}ms for {} chars",
                MaskingUtil.maskChatId(chatId), readDelayMs, typingDelayMs, text.length());

        return delay(readDelayMs)
                .thenCompose(ignored -> simulateTypingWithRefresh(chatId, typingDelayMs))
                .thenCompose(ignored -> sendWithRetry(chatId, text, 0));
    }

    /**
     * Sends "typing..." indicator and keeps it alive by resending every 4 seconds.
     * Telegram clears the indicator after ~5s, so we refresh before it expires.
     * Uses non-blocking chained delays instead of Thread.sleep to avoid blocking ForkJoinPool.
     */
    private CompletableFuture<Void> simulateTypingWithRefresh(long chatId, int totalDelayMs) {
        return simulateTypingStep(chatId, 0, totalDelayMs);
    }

    private CompletableFuture<Void> simulateTypingStep(long chatId, int elapsed, int totalDelayMs) {
        if (elapsed >= totalDelayMs) return CompletableFuture.completedFuture(null);
        sendTypingActionSync(chatId);
        int sleepMs = Math.min(4000, totalDelayMs - elapsed);
        return delay(sleepMs)
                .thenCompose(ignored -> simulateTypingStep(chatId, elapsed + sleepMs, totalDelayMs));
    }

    private void sendTypingActionSync(long chatId) {
        var action = new TdApi.SendChatAction(chatId, 0, null, new TdApi.ChatActionTyping());
        authManager.getClient().send(action, result -> {
            if (result.isError()) {
                log.debug("Failed to send typing action to chatId={}: {}", chatId, result.getError().message);
            }
        });
    }

    /**
     * Calculates a human-like typing delay based on outgoing message length.
     * Formula: clamp(msPerChar * length + random jitter, minTyping, maxTyping)
     *
     * Examples (with defaults 120ms/char, min 3s, max 55s):
     *   "Привет)" (7 chars)              → ~3s (hits min)
     *   "Видел ваше резюме..." (50 chars) → ~7s
     *   Long pitch (300 chars)            → ~38s
     */
    private int calculateTypingDelay(String text) {
        int baseDelay = msPerChar * text.length();
        int jitter = ThreadLocalRandom.current().nextInt(1000, 3000);
        return Math.clamp(baseDelay + jitter, minTypingMs, maxTypingMs);
    }

    private CompletableFuture<TdApi.Chat> searchPublicChat(String username) {
        var request = new TdApi.SearchPublicChat(username);
        var future = new CompletableFuture<TdApi.Chat>();

        authManager.getClient().send(request, result -> {
            if (result.isError()) {
                var error = result.getError();
                log.error("Failed to resolve username @{}: {} (code {})", username, error.message, error.code);
                future.completeExceptionally(new TelegramException(
                        "Cannot resolve @" + username + ": " + error.message, error.code));
            } else {
                var chat = result.get();
                log.debug("Resolved @{} -> chatId={}", username, chat.id);
                future.complete(chat);
            }
        });

        return future;
    }

    private CompletableFuture<TdApi.Message> sendWithRetry(long chatId, String text, int attempt) {
        var content = new TdApi.InputMessageText(
                new TdApi.FormattedText(text, new TdApi.TextEntity[0]),
                null,
                false
        );
        var request = new TdApi.SendMessage(chatId, 0, null, null, null, content);
        var future = new CompletableFuture<TdApi.Message>();

        authManager.getClient().send(request, result -> {
            if (result.isError()) {
                var error = result.getError();

                if (attempt < MAX_RETRIES && isRetryable(error.code)) {
                    long delayMs = BASE_RETRY_DELAY_MS * (1L << attempt); // 2s, 4s, 8s
                    log.warn("Send to chatId={} failed (code {}), retry {}/{} in {}ms: {}",
                            chatId, error.code, attempt + 1, MAX_RETRIES, delayMs, error.message);

                    delay((int) delayMs)
                            .thenCompose(ignored -> sendWithRetry(chatId, text, attempt + 1))
                            .whenComplete((msg, ex) -> {
                                if (ex != null) future.completeExceptionally(ex);
                                else future.complete(msg);
                            });
                } else {
                    log.error("Failed to send message to chatId={}: {} (code {})", chatId, error.message, error.code);
                    future.completeExceptionally(new TelegramException(
                            "Send failed: " + error.message, error.code));
                }
            } else {
                log.info("Message sent to chatId={}", MaskingUtil.maskChatId(chatId));
                future.complete(result.get());
            }
        });

        return future;
    }

    private boolean isRetryable(int errorCode) {
        return switch (errorCode) {
            case 429 -> true;  // Too Many Requests (FLOOD_WAIT)
            case 500 -> true;  // Internal Server Error
            case 420 -> true;  // FLOOD
            default -> false;  // 400, 403, 404 — permanent errors
        };
    }

    private CompletableFuture<Void> delay(int ms) {
        return CompletableFuture.runAsync(() -> {}, CompletableFuture.delayedExecutor(ms, TimeUnit.MILLISECONDS));
    }

    public static class TelegramException extends RuntimeException {
        private final int errorCode;

        public TelegramException(String message, int errorCode) {
            super(message);
            this.errorCode = errorCode;
        }

        public int getErrorCode() {
            return errorCode;
        }
    }
}
