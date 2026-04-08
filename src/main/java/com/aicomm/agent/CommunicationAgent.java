package com.aicomm.agent;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * LangChain4j AI Service interface for communication with candidates.
 * Each conversation has its own memory (by conversationId).
 * System prompt is injected dynamically per persona via @V("systemPrompt").
 */
public interface CommunicationAgent {

    @SystemMessage("{{systemPrompt}}")
    String chat(@MemoryId Long conversationId,
                @V("systemPrompt") String systemPrompt,
                @UserMessage String userMessage);
}
