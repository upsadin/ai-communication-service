package com.aicomm.repository;

import com.aicomm.domain.ChannelType;
import com.aicomm.domain.Conversation;
import com.aicomm.domain.ConversationStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ConversationRepository extends JpaRepository<Conversation, Long> {

    Optional<Conversation> findBySourceId(String sourceId);

    Optional<Conversation> findByChannelTypeAndContactIdAndStatus(
            ChannelType channelType, String contactId, ConversationStatus status);

    Optional<Conversation> findFirstByChannelTypeAndContactIdAndStatusInOrderByCreatedAtDesc(
            ChannelType channelType, String contactId, List<ConversationStatus> statuses);
}
