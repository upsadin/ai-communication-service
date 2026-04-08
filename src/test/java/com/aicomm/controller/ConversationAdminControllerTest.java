package com.aicomm.controller;

import com.aicomm.domain.ChannelType;
import com.aicomm.domain.Conversation;
import com.aicomm.domain.ConversationMessage;
import com.aicomm.domain.ConversationStatus;
import com.aicomm.repository.ConversationMessageRepository;
import com.aicomm.repository.ConversationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConversationAdminControllerTest {

    @Mock private ConversationRepository conversationRepository;
    @Mock private ConversationMessageRepository messageRepository;

    @InjectMocks
    private ConversationAdminController controller;

    private Conversation conversation;

    @BeforeEach
    void setUp() {
        conversation = new Conversation();
        conversation.setId(1L);
        conversation.setSourceId("src-1");
        conversation.setRef("candidate_java");
        conversation.setFullName("Viktor Vikulitko");
        conversation.setContactId("491865728");
        conversation.setChannelType(ChannelType.TELEGRAM);
        conversation.setStatus(ConversationStatus.ACTIVE);
    }

    @Test
    void listAll_returnsMaskedSummaries() {
        when(conversationRepository.findAll()).thenReturn(List.of(conversation));

        var result = controller.listAll();

        assertThat(result).hasSize(1);
        var dto = result.get(0);
        assertThat(dto.ref()).isEqualTo("candidate_java");
        assertThat(dto.maskedName()).isEqualTo("V. V***");
        assertThat(dto.maskedContactId()).isEqualTo("4918***728");
        assertThat(dto.status()).isEqualTo("ACTIVE");
    }

    @Test
    void getById_returnsDetailWithMessages() {
        var msg1 = new ConversationMessage(conversation, "USER", "Привет");
        var msg2 = new ConversationMessage(conversation, "ASSISTANT", "Здравствуйте!");

        when(conversationRepository.findById(1L)).thenReturn(Optional.of(conversation));
        when(messageRepository.findByConversationIdOrderByCreatedAtAsc(1L)).thenReturn(List.of(msg1, msg2));

        var response = controller.getById(1L);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        var detail = response.getBody();
        assertThat(detail.conversation().maskedName()).isEqualTo("V. V***");
        assertThat(detail.messages()).hasSize(2);
        assertThat(detail.messages().get(0).role()).isEqualTo("USER");
        assertThat(detail.messages().get(0).content()).isEqualTo("Привет");
        assertThat(detail.messages().get(1).role()).isEqualTo("ASSISTANT");
    }

    @Test
    void getById_returns404_whenNotFound() {
        when(conversationRepository.findById(999L)).thenReturn(Optional.empty());

        var response = controller.getById(999L);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void findByStatus_filtersCorrectly() {
        var completed = new Conversation();
        completed.setId(2L);
        completed.setSourceId("src-2");
        completed.setRef("candidate_go");
        completed.setFullName("Anna");
        completed.setContactId("123456789");
        completed.setChannelType(ChannelType.TELEGRAM);
        completed.setStatus(ConversationStatus.COMPLETED);

        when(conversationRepository.findAll()).thenReturn(List.of(conversation, completed));

        var active = controller.findByStatus("ACTIVE");
        assertThat(active).hasSize(1);
        assertThat(active.get(0).id()).isEqualTo(1L);

        var completedList = controller.findByStatus("COMPLETED");
        assertThat(completedList).hasSize(1);
        assertThat(completedList.get(0).id()).isEqualTo(2L);
    }
}
