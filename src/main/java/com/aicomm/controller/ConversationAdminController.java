package com.aicomm.controller;

import com.aicomm.domain.Conversation;
import com.aicomm.domain.ConversationMessage;
import com.aicomm.repository.ConversationMessageRepository;
import com.aicomm.repository.ConversationRepository;
import com.aicomm.util.MaskingUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

/**
 * Admin API for viewing conversations and their messages.
 * Contact IDs and names are masked in responses for privacy.
 */
@RestController
@RequestMapping("/internal/admin/conversations")
@RequiredArgsConstructor
public class ConversationAdminController {

    private final ConversationRepository conversationRepository;
    private final ConversationMessageRepository messageRepository;

    @GetMapping
    public List<ConversationSummaryDto> listAll() {
        return conversationRepository.findAll().stream()
                .map(ConversationSummaryDto::from)
                .toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<ConversationDetailDto> getById(@PathVariable Long id) {
        return conversationRepository.findById(id)
                .map(conv -> {
                    var messages = messageRepository.findByConversationIdOrderByCreatedAtAsc(id);
                    return ResponseEntity.ok(ConversationDetailDto.from(conv, messages));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/status/{status}")
    public List<ConversationSummaryDto> findByStatus(@PathVariable String status) {
        return conversationRepository.findAll().stream()
                .filter(c -> c.getStatus().name().equalsIgnoreCase(status))
                .map(ConversationSummaryDto::from)
                .toList();
    }

    public record ConversationSummaryDto(Long id, String sourceId, String ref,
                                          String maskedName, String maskedContactId,
                                          String channelType, String status,
                                          Instant createdAt, Instant updatedAt) {
        public static ConversationSummaryDto from(Conversation c) {
            return new ConversationSummaryDto(
                    c.getId(), c.getSourceId(), c.getRef(),
                    MaskingUtil.maskName(c.getFullName()),
                    MaskingUtil.maskContactId(c.getContactId()),
                    c.getChannelType().name(), c.getStatus().name(),
                    c.getCreatedAt(), c.getUpdatedAt()
            );
        }
    }

    public record ConversationDetailDto(ConversationSummaryDto conversation, List<MessageDto> messages) {
        public static ConversationDetailDto from(Conversation c, List<ConversationMessage> msgs) {
            return new ConversationDetailDto(
                    ConversationSummaryDto.from(c),
                    msgs.stream().map(MessageDto::from).toList()
            );
        }
    }

    public record MessageDto(Long id, String role, String content, Instant createdAt) {
        public static MessageDto from(ConversationMessage m) {
            return new MessageDto(m.getId(), m.getRole(), m.getContent(), m.getCreatedAt());
        }
    }
}
