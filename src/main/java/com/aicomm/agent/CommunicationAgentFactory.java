package com.aicomm.agent;

import com.aicomm.conversation.memory.JpaChatMemoryStore;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.service.AiServices;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Provides a singleton CommunicationAgent instance.
 *
 * Agent is stateless — system prompt and conversationId are passed per-call via @V and @MemoryId.
 * ChatMemoryProvider creates a separate memory window per conversationId (loaded from DB).
 * One agent safely handles all conversations in parallel.
 */
@Slf4j
@Component
public class CommunicationAgentFactory {

    @Getter
    private final CommunicationAgent agent;

    public CommunicationAgentFactory(ChatLanguageModel chatLanguageModel,
                                     JpaChatMemoryStore chatMemoryStore,
                                     @Value("${app.agent.memory-max-messages:20}") int memoryMaxMessages) {
        this.agent = AiServices.builder(CommunicationAgent.class)
                .chatLanguageModel(chatLanguageModel)
                .chatMemoryProvider(memoryId -> MessageWindowChatMemory.builder()
                        .id(memoryId)
                        .maxMessages(memoryMaxMessages)
                        .chatMemoryStore(chatMemoryStore)
                        .build())
                .build();

        log.info("CommunicationAgent created (singleton, memoryMaxMessages={})", memoryMaxMessages);
    }
}
