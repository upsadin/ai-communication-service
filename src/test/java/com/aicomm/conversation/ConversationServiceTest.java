package com.aicomm.conversation;

import com.aicomm.domain.ChannelType;
import com.aicomm.domain.Conversation;
import com.aicomm.domain.ConversationMessage;
import com.aicomm.domain.ConversationStatus;
import com.aicomm.repository.ConversationMessageRepository;
import com.aicomm.repository.ConversationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ConversationServiceTest {

    @Mock
    private ConversationRepository conversationRepository;

    @Mock
    private ConversationMessageRepository messageRepository;

    @InjectMocks
    private ConversationService conversationService;

    @Test
    void createConversation_setsFieldsAndSaves() {
        when(conversationRepository.save(any(Conversation.class))).thenAnswer(inv -> {
            var conv = inv.<Conversation>getArgument(0);
            conv.setId(1L);
            return conv;
        });

        var result = conversationService.createConversation(
                "src-1", "candidate_java", "Viktor", ChannelType.TELEGRAM, "12345", "{\"context\":\"test\"}");

        assertThat(result.getId()).isEqualTo(1L);
        assertThat(result.getSourceId()).isEqualTo("src-1");
        assertThat(result.getRef()).isEqualTo("candidate_java");
        assertThat(result.getFullName()).isEqualTo("Viktor");
        assertThat(result.getChannelType()).isEqualTo(ChannelType.TELEGRAM);
        assertThat(result.getContactId()).isEqualTo("12345");
        assertThat(result.getStatus()).isEqualTo(ConversationStatus.INITIATED);
    }

    @Test
    void addMessage_savesViaMessageRepository() {
        var conversation = new Conversation();
        conversation.setId(1L);

        conversationService.addMessage(conversation, "USER", "hello");

        var captor = ArgumentCaptor.forClass(ConversationMessage.class);
        verify(messageRepository).save(captor.capture());

        var saved = captor.getValue();
        assertThat(saved.getConversation()).isEqualTo(conversation);
        assertThat(saved.getRole()).isEqualTo("USER");
        assertThat(saved.getContent()).isEqualTo("hello");
    }

    @Test
    void updateStatus_changesStatusAndSaves() {
        var conversation = new Conversation();
        conversation.setId(1L);
        conversation.setStatus(ConversationStatus.INITIATED);

        when(conversationRepository.findById(1L)).thenReturn(java.util.Optional.of(conversation));
        conversationService.updateStatus(1L, ConversationStatus.ACTIVE);

        assertThat(conversation.getStatus()).isEqualTo(ConversationStatus.ACTIVE);
        verify(conversationRepository).save(conversation);
    }

    @Test
    void findActiveByContact_delegatesToRepository() {
        var conv = new Conversation();
        when(conversationRepository.findFirstByChannelTypeAndContactIdAndStatusInOrderByCreatedAtDesc(
                eq(ChannelType.TELEGRAM), eq("12345"), anyList()))
                .thenReturn(Optional.of(conv));

        var result = conversationService.findActiveByContact(ChannelType.TELEGRAM, "12345");

        assertThat(result).isPresent().contains(conv);
    }
}
