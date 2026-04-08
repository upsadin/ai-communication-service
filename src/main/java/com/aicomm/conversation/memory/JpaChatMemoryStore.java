package com.aicomm.conversation.memory;

import com.aicomm.domain.ConversationMessage;
import com.aicomm.repository.ConversationMessageRepository;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Stores LangChain4j chat memory in PostgreSQL via conversation_messages table.
 * Memory ID = conversation ID (Long).
 *
 * This allows the AI agent to maintain context across application restarts.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JpaChatMemoryStore implements ChatMemoryStore {

    private final ConversationMessageRepository messageRepository;

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        var conversationId = toLong(memoryId);
        var dbMessages = messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId);

        List<ChatMessage> messages = new ArrayList<>(dbMessages.size());
        for (var dbMsg : dbMessages) {
            var chatMessage = toChatMessage(dbMsg);
            if (chatMessage != null) {
                messages.add(chatMessage);
            }
        }

        log.debug("Loaded {} messages for conversationId={}", messages.size(), conversationId);
        return messages;
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        // LangChain4j calls this after each interaction.
        // We save messages individually in ConversationService instead,
        // so this is intentionally a no-op to avoid duplicates.
        log.debug("updateMessages called for memoryId={} (no-op, saved via ConversationService)", memoryId);
    }

    @Override
    public void deleteMessages(Object memoryId) {
        log.debug("deleteMessages called for memoryId={} (no-op)", memoryId);
    }

    private ChatMessage toChatMessage(ConversationMessage dbMsg) {
        return switch (dbMsg.getRole()) {
            case "USER" -> UserMessage.from(dbMsg.getContent());
            case "ASSISTANT" -> AiMessage.from(dbMsg.getContent());
            case "SYSTEM" -> SystemMessage.from(dbMsg.getContent());
            default -> {
                log.warn("Unknown message role: {}", dbMsg.getRole());
                yield null;
            }
        };
    }

    private Long toLong(Object memoryId) {
        if (memoryId instanceof Long l) return l;
        return Long.parseLong(memoryId.toString());
    }
}
