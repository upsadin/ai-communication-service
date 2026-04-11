package com.aicomm.conversation;

import com.aicomm.agent.CommunicationAgent;
import com.aicomm.agent.CommunicationAgentFactory;
import com.aicomm.domain.ChannelType;
import com.aicomm.domain.Conversation;
import com.aicomm.domain.ConversationStatus;
import com.aicomm.domain.Persona;
import com.aicomm.kafka.dto.MessageProcessingTask;
import com.aicomm.persona.PersonaService;
import com.aicomm.telegram.TelegramClientService;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FirstContactServiceTest {

    @Mock private PersonaService personaService;
    @Mock private ConversationService conversationService;
    @Mock private CommunicationAgentFactory agentFactory;
    @Mock private TelegramClientService telegramClientService;
    @Mock private CommunicationAgent agent;

    @InjectMocks
    private FirstContactService firstContactService;

    private final ObjectMapper mapper = new ObjectMapper();
    private Persona persona;
    private Conversation conversation;

    @BeforeEach
    void setUp() {
        persona = new Persona();
        persona.setRef("candidate_java");
        persona.setSystemPrompt("Ты — Анна, HR");
        persona.setFirstMessageTemplate("Привет, {{name}}! Ты подходишь потому что: {{reason}}");
        persona.setFieldMapping("{\"nameField\":\"full_name\",\"contactField\":\"contacts.telegram\",\"reasonField\":\"reason\"}");

        conversation = new Conversation();
        conversation.setId(1L);
        conversation.setContactId("12345");
    }

    @Test
    void initiateContact_fullPipeline() {
        var contacts = mapper.createObjectNode().put("telegram", "12345");
        var aiResult = mapper.createObjectNode()
                .put("full_name", "Viktor")
                .put("reason", "Java expert");
        aiResult.set("contacts", contacts);

        var task = new MessageProcessingTask("candidate_java", "src-1", aiResult);

        when(personaService.getByRef("candidate_java")).thenReturn(Optional.of(persona));
        when(conversationService.createConversation(any(), any(), any(), any(), any(), any())).thenReturn(conversation);
        when(agentFactory.getAgent()).thenReturn(agent);
        when(agent.chat(eq(1L), contains("Ты — Анна, HR"), anyString())).thenReturn("Привет, Виктор!");
        when(telegramClientService.sendMessage(eq("12345"), eq("Привет, Виктор!")))
                .thenReturn(CompletableFuture.completedFuture(null));

        firstContactService.initiateContact(task);

        // Verify conversation created with extracted fields
        verify(conversationService).createConversation(eq("src-1"), eq("candidate_java"), eq("Viktor"), eq(ChannelType.TELEGRAM), eq("12345"), any());

        // Verify template rendered and saved as USER message
        var promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(conversationService, times(2)).addMessage(eq(conversation), anyString(), promptCaptor.capture());
        var savedPrompt = promptCaptor.getAllValues().get(0);
        assertThat(savedPrompt).contains("Viktor").contains("Java expert");

        // Verify AI response saved
        var savedResponse = promptCaptor.getAllValues().get(1);
        assertThat(savedResponse).isEqualTo("Привет, Виктор!");

        // Verify sent to Telegram
        verify(telegramClientService).sendMessage("12345", "Привет, Виктор!");

        // Verify status updated to ACTIVE
        verify(conversationService).updateStatus(1L, ConversationStatus.ACTIVE);
    }

    @Test
    void initiateContact_throwsIfPersonaNotFound() {
        var aiResult = mapper.createObjectNode().put("contactId", "12345");
        var task = new MessageProcessingTask("unknown_ref", "src-1", aiResult);

        when(personaService.getByRef("unknown_ref")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> firstContactService.initiateContact(task))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Persona not found");
    }

    @Test
    void initiateContact_setsStatusFailed_onTelegramError() {
        var contacts = mapper.createObjectNode().put("telegram", "12345");
        var aiResult = mapper.createObjectNode()
                .put("full_name", "Viktor")
                .put("reason", "Java");
        aiResult.set("contacts", contacts);

        var task = new MessageProcessingTask("candidate_java", "src-1", aiResult);

        when(personaService.getByRef("candidate_java")).thenReturn(Optional.of(persona));
        when(conversationService.createConversation(any(), any(), any(), any(), any(), any())).thenReturn(conversation);
        when(agentFactory.getAgent()).thenReturn(agent);
        when(agent.chat(eq(1L), anyString(), anyString())).thenReturn("Привет!");
        when(telegramClientService.sendMessage(anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("Telegram error")));

        firstContactService.initiateContact(task);

        verify(conversationService).updateStatus(1L, ConversationStatus.FAILED);
    }

    @Test
    void renderTemplate_replacesAllPlaceholders() {
        persona.setFirstMessageTemplate("Имя: {{name}}, причина: {{reason}}, город: {{city}}");

        var contacts = mapper.createObjectNode().put("telegram", "999");
        var aiResult = mapper.createObjectNode()
                .put("full_name", "Anna")
                .put("reason", "Go expert")
                .put("city", "Москва");
        aiResult.set("contacts", contacts);

        var task = new MessageProcessingTask("candidate_java", "src-1", aiResult);

        when(personaService.getByRef("candidate_java")).thenReturn(Optional.of(persona));
        when(conversationService.createConversation(any(), any(), any(), any(), any(), any())).thenReturn(conversation);
        when(agentFactory.getAgent()).thenReturn(agent);
        when(agent.chat(eq(1L), anyString(), anyString())).thenReturn("ok");
        when(telegramClientService.sendMessage(anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        firstContactService.initiateContact(task);

        var promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(conversationService, atLeastOnce()).addMessage(eq(conversation), eq("USER"), promptCaptor.capture());
        var rendered = promptCaptor.getValue();
        assertThat(rendered).contains("Anna").contains("Go expert").contains("Москва");
    }
}
