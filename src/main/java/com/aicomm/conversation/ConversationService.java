package com.aicomm.conversation;

import com.aicomm.domain.ChannelType;
import com.aicomm.domain.Conversation;
import com.aicomm.domain.ConversationMessage;
import com.aicomm.domain.ConversationStatus;
import com.aicomm.repository.ConversationMessageRepository;
import com.aicomm.repository.ConversationRepository;
import com.aicomm.util.MaskingUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Manages conversation lifecycle: creation, message appending, status transitions.
 * Saves messages directly via ConversationMessageRepository to avoid
 * LazyInitializationException in async contexts.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationService {

    private final ConversationRepository conversationRepository;
    private final ConversationMessageRepository messageRepository;

    @Transactional
    public Conversation createConversation(String sourceId, String ref, String fullName,
                                           ChannelType channelType, String contactId,
                                           String candidateContext) {
        var conversation = new Conversation();
        conversation.setSourceId(sourceId);
        conversation.setRef(ref);
        conversation.setFullName(fullName);
        conversation.setChannelType(channelType);
        conversation.setContactId(contactId);
        conversation.setCandidateContext(candidateContext);
        conversation.setStatus(ConversationStatus.INITIATED);

        conversation = conversationRepository.save(conversation);
        log.info("Created conversation id={}, sourceId={}, ref={}, contactId={}",
                conversation.getId(), sourceId, ref, MaskingUtil.maskContactId(contactId));
        return conversation;
    }

    @Transactional
    public void addMessage(Conversation conversation, String role, String content) {
        var message = new ConversationMessage(conversation, role, content);
        messageRepository.save(message);
    }

    @Transactional
    public void updateStatus(Conversation conversation, ConversationStatus status) {
        conversation.setStatus(status);
        conversationRepository.save(conversation);
        log.info("Conversation id={} status -> {}", conversation.getId(), status);
    }

    public Optional<Conversation> findActiveByContact(ChannelType channelType, String contactId) {
        return conversationRepository.findByChannelTypeAndContactIdAndStatus(
                channelType, contactId, ConversationStatus.ACTIVE);
    }

    public Optional<Conversation> findBySourceId(String sourceId) {
        return conversationRepository.findBySourceId(sourceId);
    }
}
