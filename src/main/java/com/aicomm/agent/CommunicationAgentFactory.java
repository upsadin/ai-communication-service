package com.aicomm.agent;

import com.aicomm.agent.tools.ConversationTools;
import com.aicomm.conversation.memory.JpaChatMemoryStore;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Provides a singleton CommunicationAgent instance with @Tool support.
 *
 * Agent is stateless — system prompt and conversationId are passed per-call via @V and @MemoryId.
 * Tools access current conversation via ConversationContext (ThreadLocal).
 * One agent safely handles all conversations in parallel.
 */
@Slf4j
@Component
public class CommunicationAgentFactory {

    @Getter
    private final CommunicationAgent agent;

    public CommunicationAgentFactory(ChatModel chatModel,
                                     JpaChatMemoryStore chatMemoryStore,
                                     ConversationTools conversationTools,
                                     @Value("${app.agent.memory-max-messages:20}") int memoryMaxMessages) {
        this.agent = AiServices.builder(CommunicationAgent.class)
                .chatModel(chatModel)
                .chatMemoryProvider(memoryId -> MessageWindowChatMemory.builder()
                        .id(memoryId)
                        .maxMessages(memoryMaxMessages)
                        .chatMemoryStore(chatMemoryStore)
                        .build())
                .tools(conversationTools)
                .maxSequentialToolsInvocations(3)
                .build();

        log.info("CommunicationAgent created (singleton, memoryMaxMessages={}, tools=enabled)", memoryMaxMessages);
    }
}
