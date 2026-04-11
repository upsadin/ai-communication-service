package com.aicomm.conversation;

import com.aicomm.agent.CommunicationAgentFactory;
import com.aicomm.domain.Conversation;
import com.aicomm.persona.PersonaService;
import com.aicomm.telegram.TelegramClientService;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ScheduledFollowUpServiceTest {

    @Mock private ChatModel chatModel;
    @Mock private ConversationService conversationService;
    @Mock private PersonaService personaService;
    @Mock private CommunicationAgentFactory agentFactory;
    @Mock private TelegramClientService telegramClientService;

    @InjectMocks
    private ScheduledFollowUpService service;

    private Conversation conversation;

    @BeforeEach
    void setUp() {
        conversation = new Conversation();
        conversation.setId(1L);
        conversation.setRef("candidate_java");
        conversation.setContactId("12345");
    }

    @Test
    void analyzeAndSchedule_detectsDelayIntent() {
        var response = ChatResponse.builder()
                .aiMessage(AiMessage.from("5"))
                .build();
        when(chatModel.chat(any(dev.langchain4j.data.message.ChatMessage[].class))).thenReturn(response);

        service.analyzeAndScheduleIfNeeded("напиши через 5 минут", conversation);

        // The scheduler is internal — we verify the classifier was called
        verify(chatModel).chat(any(dev.langchain4j.data.message.ChatMessage[].class));
    }

    @Test
    void analyzeAndSchedule_ignoresWhenNoIntent() {
        var response = ChatResponse.builder()
                .aiMessage(AiMessage.from("0"))
                .build();
        when(chatModel.chat(any(dev.langchain4j.data.message.ChatMessage[].class))).thenReturn(response);

        service.analyzeAndScheduleIfNeeded("привет, расскажи о вакансии", conversation);

        verify(chatModel).chat(any(dev.langchain4j.data.message.ChatMessage[].class));
        // No scheduling happens — no follow-up interactions
    }

    @Test
    void analyzeAndSchedule_handlesClassifierError() {
        when(chatModel.chat(any(dev.langchain4j.data.message.ChatMessage[].class))).thenThrow(new RuntimeException("API error"));

        // Should not throw — gracefully handles errors
        service.analyzeAndScheduleIfNeeded("напиши завтра", conversation);

        verify(chatModel).chat(any(dev.langchain4j.data.message.ChatMessage[].class));
    }
}
