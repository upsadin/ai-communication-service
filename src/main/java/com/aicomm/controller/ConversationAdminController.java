package com.aicomm.controller;

import com.aicomm.domain.Conversation;
import com.aicomm.domain.ConversationMessage;
import com.aicomm.repository.ConversationMessageRepository;
import com.aicomm.repository.ConversationRepository;
import com.aicomm.util.MaskingUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/internal/admin/conversations")
@RequiredArgsConstructor
@Tag(name = "Conversations", description = "Просмотр диалогов с кандидатами. Контактные данные маскируются в ответах.")
public class ConversationAdminController {

    private final ConversationRepository conversationRepository;
    private final ConversationMessageRepository messageRepository;

    @GetMapping
    @Operation(summary = "Список всех диалогов", description = "Возвращает краткую информацию по всем диалогам (без сообщений)")
    public List<ConversationSummaryDto> listAll() {
        return conversationRepository.findAll().stream()
                .map(ConversationSummaryDto::from)
                .toList();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Детали диалога", description = "Возвращает диалог с полной историей сообщений")
    @ApiResponse(responseCode = "200", description = "Диалог найден")
    @ApiResponse(responseCode = "404", description = "Диалог не найден")
    public ResponseEntity<ConversationDetailDto> getById(
            @Parameter(description = "ID диалога", example = "1") @PathVariable Long id) {
        return conversationRepository.findById(id)
                .map(conv -> {
                    var messages = messageRepository.findByConversationIdOrderByCreatedAtAsc(id);
                    return ResponseEntity.ok(ConversationDetailDto.from(conv, messages));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/status/{status}")
    @Operation(summary = "Диалоги по статусу",
            description = "Фильтрует диалоги по статусу: INITIATED, ACTIVE, TEST_SENT, COMPLETED, FAILED, TIMED_OUT")
    public List<ConversationSummaryDto> findByStatus(
            @Parameter(description = "Статус диалога", example = "ACTIVE") @PathVariable String status) {
        return conversationRepository.findAll().stream()
                .filter(c -> c.getStatus().name().equalsIgnoreCase(status))
                .map(ConversationSummaryDto::from)
                .toList();
    }

    @Schema(description = "Краткая информация о диалоге")
    public record ConversationSummaryDto(
            @Schema(description = "ID диалога", example = "1") Long id,
            @Schema(description = "ID из upstream-сервиса", example = "src-12345") String sourceId,
            @Schema(description = "Ref персоны", example = "candidate_java") String ref,
            @Schema(description = "Замаскированное имя", example = "Пав***") String maskedName,
            @Schema(description = "Замаскированный контакт", example = "***8176") String maskedContactId,
            @Schema(description = "Канал связи", example = "TELEGRAM") String channelType,
            @Schema(description = "Статус диалога", example = "ACTIVE") String status,
            @Schema(description = "Дата создания") Instant createdAt,
            @Schema(description = "Дата обновления") Instant updatedAt) {
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

    @Schema(description = "Диалог с полной историей сообщений")
    public record ConversationDetailDto(ConversationSummaryDto conversation, List<MessageDto> messages) {
        public static ConversationDetailDto from(Conversation c, List<ConversationMessage> msgs) {
            return new ConversationDetailDto(
                    ConversationSummaryDto.from(c),
                    msgs.stream().map(MessageDto::from).toList()
            );
        }
    }

    @Schema(description = "Сообщение в диалоге")
    public record MessageDto(
            @Schema(description = "ID сообщения", example = "1") Long id,
            @Schema(description = "Роль: USER, ASSISTANT, SYSTEM", example = "ASSISTANT") String role,
            @Schema(description = "Текст сообщения") String content,
            @Schema(description = "Дата отправки") Instant createdAt) {
        public static MessageDto from(ConversationMessage m) {
            return new MessageDto(m.getId(), m.getRole(), m.getContent(), m.getCreatedAt());
        }
    }
}
