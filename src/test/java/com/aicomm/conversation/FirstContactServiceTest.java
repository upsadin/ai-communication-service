package com.aicomm.conversation;

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
    @Mock private TelegramClientService telegramClientService;

    @InjectMocks
    private FirstContactService firstContactService;

    private final ObjectMapper mapper = new ObjectMapper();
    private Persona persona;
    private Conversation conversation;

    @BeforeEach
    void setUp() {
        persona = new Persona();
        persona.setRef("candidate_java");
        persona.setSystemPrompt("Ты — Олег, HR");
        persona.setFirstMessageTemplate("Привет, {{name}}! Ты подходишь потому что: {{reason}}");
        persona.setFieldMapping("{\"nameField\":\"full_name\",\"contactField\":\"contacts.telegram\",\"reasonField\":\"reason\"}");

        conversation = new Conversation();
        conversation.setId(1L);
        conversation.setContactId("12345");
    }

    @Test
    void initiateContact_sendsRenderedTemplateDirectly() {
        var contacts = mapper.createObjectNode().put("telegram", "12345");
        var aiResult = mapper.createObjectNode()
                .put("full_name", "Viktor Ivanov")
                .put("reason", "Java expert");
        aiResult.set("contacts", contacts);

        var task = new MessageProcessingTask("candidate_java", "src-1", aiResult);

        when(personaService.getByRef("candidate_java")).thenReturn(Optional.of(persona));
        when(conversationService.createConversation(any(), any(), any(), any(), any(), any())).thenReturn(conversation);
        when(telegramClientService.sendMessage(eq("12345"), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        firstContactService.initiateContact(task);

        // Verify conversation created with extracted fields
        verify(conversationService).createConversation(eq("src-1"), eq("candidate_java"), eq("Viktor Ivanov"), eq(ChannelType.TELEGRAM), eq("12345"), any());

        // Verify rendered template saved as ASSISTANT (only 1 addMessage call)
        var contentCaptor = ArgumentCaptor.forClass(String.class);
        verify(conversationService, times(1)).addMessage(eq(conversation), eq("ASSISTANT"), contentCaptor.capture());
        var savedMessage = contentCaptor.getValue();
        assertThat(savedMessage).contains("Viktor").contains("Java expert");
        // First name only — no last name in {{name}}
        assertThat(savedMessage).startsWith("Привет, Viktor!");

        // Verify sent to Telegram (same rendered text)
        verify(telegramClientService).sendMessage("12345", savedMessage);

        // Verify status updated to ACTIVE
        verify(conversationService).updateStatus(1L, ConversationStatus.ACTIVE);
    }

    @Test
    void initiateContact_usesFirstNameOnly() {
        persona.setFirstMessageTemplate("Здравствуйте, {{name}}!");

        var contacts = mapper.createObjectNode().put("telegram", "12345");
        var aiResult = mapper.createObjectNode().put("full_name", "Павел Иванов");
        aiResult.set("contacts", contacts);

        var task = new MessageProcessingTask("candidate_java", "src-1", aiResult);

        when(personaService.getByRef("candidate_java")).thenReturn(Optional.of(persona));
        when(conversationService.createConversation(any(), any(), any(), any(), any(), any())).thenReturn(conversation);
        when(telegramClientService.sendMessage(anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        firstContactService.initiateContact(task);

        var contentCaptor = ArgumentCaptor.forClass(String.class);
        verify(conversationService).addMessage(eq(conversation), eq("ASSISTANT"), contentCaptor.capture());
        assertThat(contentCaptor.getValue()).isEqualTo("Здравствуйте, Павел!");
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
                .put("full_name", "Anna Petrova")
                .put("reason", "Go expert")
                .put("city", "Москва");
        aiResult.set("contacts", contacts);

        var task = new MessageProcessingTask("candidate_java", "src-1", aiResult);

        when(personaService.getByRef("candidate_java")).thenReturn(Optional.of(persona));
        when(conversationService.createConversation(any(), any(), any(), any(), any(), any())).thenReturn(conversation);
        when(telegramClientService.sendMessage(anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        firstContactService.initiateContact(task);

        var contentCaptor = ArgumentCaptor.forClass(String.class);
        verify(conversationService).addMessage(eq(conversation), eq("ASSISTANT"), contentCaptor.capture());
        var rendered = contentCaptor.getValue();
        assertThat(rendered).contains("Anna").contains("Go expert").contains("Москва");
        // First name only
        assertThat(rendered).doesNotContain("Petrova");
    }
}
