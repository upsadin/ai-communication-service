package com.aicomm.conversation;

import com.aicomm.agent.CommunicationAgent;
import com.aicomm.agent.CommunicationAgentFactory;
import com.aicomm.domain.ChannelType;
import com.aicomm.domain.Conversation;
import com.aicomm.domain.ConversationStatus;
import com.aicomm.domain.Persona;
import com.aicomm.persona.PersonaService;
import com.aicomm.schedule.DeferredMessageService;
import com.aicomm.telegram.TelegramClientService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ConversationContinuationServiceTest {

    @Mock private ConversationService conversationService;
    @Mock private PersonaService personaService;
    @Mock private CommunicationAgentFactory agentFactory;
    @Mock private TelegramClientService telegramClientService;
    @Mock private DeferredMessageService deferredMessageService;
    @Mock private ScheduledFollowUpService scheduledFollowUpService;
    @Mock private CommunicationAgent agent;

    @InjectMocks
    private ConversationContinuationService service;

    private Persona persona;
    private Conversation conversation;

    @BeforeEach
    void setUp() {
        persona = new Persona();
        persona.setRef("candidate_java");
        persona.setSystemPrompt("Ты — Анна");

        conversation = new Conversation();
        conversation.setId(1L);
        conversation.setRef("candidate_java");
        conversation.setContactId("12345");
        conversation.setStatus(ConversationStatus.ACTIVE);
    }

    @Test
    void handleIncomingReply_ignoresWhenNoActiveConversation() {
        when(conversationService.findActiveByContact(ChannelType.TELEGRAM, "99999"))
                .thenReturn(Optional.empty());

        service.handleIncomingReply(99999L, "hello");

        verifyNoInteractions(agentFactory, telegramClientService);
    }

    @Test
    void handleIncomingReply_savesUserMessage_immediatelyBeforeDefer() {
        when(conversationService.findActiveByContact(ChannelType.TELEGRAM, "12345"))
                .thenReturn(Optional.of(conversation));
        when(personaService.getByRef("candidate_java")).thenReturn(Optional.of(persona));
        // executeOrDefer runs immediately
        when(deferredMessageService.executeOrDefer(anyString(), any(), any())).thenAnswer(inv -> {
            inv.<Runnable>getArgument(2).run();
            return true;
        });
        when(agentFactory.getAgent()).thenReturn(agent);
        when(agent.chat(eq(1L), anyString(), anyString())).thenReturn("AI reply");
        when(telegramClientService.sendMessageByChatId(anyLong(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        service.handleIncomingReply(12345L, "test message");

        // USER message saved first
        var roleCaptor = ArgumentCaptor.forClass(String.class);
        var contentCaptor = ArgumentCaptor.forClass(String.class);
        verify(conversationService, atLeast(2)).addMessage(eq(conversation), roleCaptor.capture(), contentCaptor.capture());

        assertThat(roleCaptor.getAllValues().get(0)).isEqualTo("USER");
        assertThat(contentCaptor.getAllValues().get(0)).isEqualTo("test message");
        assertThat(roleCaptor.getAllValues().get(1)).isEqualTo("ASSISTANT");
        assertThat(contentCaptor.getAllValues().get(1)).isEqualTo("AI reply");
    }

    @Test
    void handleIncomingReply_callsScheduleAnalysis() {
        when(conversationService.findActiveByContact(ChannelType.TELEGRAM, "12345"))
                .thenReturn(Optional.of(conversation));
        when(personaService.getByRef("candidate_java")).thenReturn(Optional.of(persona));
        when(deferredMessageService.executeOrDefer(anyString(), any(), any())).thenAnswer(inv -> {
            inv.<Runnable>getArgument(2).run();
            return true;
        });
        when(agentFactory.getAgent()).thenReturn(agent);
        when(agent.chat(eq(1L), anyString(), anyString())).thenReturn("ok");
        when(telegramClientService.sendMessageByChatId(anyLong(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        service.handleIncomingReply(12345L, "напиши через 5 минут");

        verify(scheduledFollowUpService).analyzeAndScheduleIfNeeded("напиши через 5 минут", conversation);
    }

    @Test
    void handleIncomingReply_defersIfOffHours() {
        when(conversationService.findActiveByContact(ChannelType.TELEGRAM, "12345"))
                .thenReturn(Optional.of(conversation));
        when(personaService.getByRef("candidate_java")).thenReturn(Optional.of(persona));
        // executeOrDefer does NOT run — defers
        when(deferredMessageService.executeOrDefer(anyString(), any(), any())).thenReturn(false);

        service.handleIncomingReply(12345L, "hello");

        // USER message saved immediately
        verify(conversationService).addMessage(conversation, "USER", "hello");
        // But AI not called (deferred)
        verifyNoInteractions(agentFactory);
    }
}
