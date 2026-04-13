package com.aicomm.controller;

import com.aicomm.domain.Persona;
import com.aicomm.persona.PersonaService;
import com.aicomm.repository.PersonaRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/internal/admin/personas")
@RequiredArgsConstructor
@Tag(name = "Personas", description = "CRUD-управление персонами (HR-агентами). Позволяет менять промпты, шаблоны сообщений и настройки без редеплоя.")
public class PersonaAdminController {

    private final PersonaRepository personaRepository;
    private final PersonaService personaService;

    @GetMapping
    @Operation(summary = "Список всех персон", description = "Возвращает все персоны, включая неактивные")
    public List<PersonaDto> listAll() {
        return personaRepository.findAll().stream()
                .map(PersonaDto::from)
                .toList();
    }

    @GetMapping("/{ref}")
    @Operation(summary = "Получить персону по ref", description = "Возвращает активную персону по уникальному ref-идентификатору")
    @ApiResponse(responseCode = "200", description = "Персона найдена")
    @ApiResponse(responseCode = "404", description = "Персона не найдена или неактивна")
    public ResponseEntity<PersonaDto> getByRef(
            @Parameter(description = "Уникальный ref персоны", example = "candidate_java") @PathVariable String ref) {
        return personaRepository.findByRefAndActiveTrue(ref)
                .map(PersonaDto::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    @Operation(summary = "Создать персону", description = "Создаёт новую персону с промптом, шаблонами сообщений и маппингом полей")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            content = @Content(examples = @ExampleObject(value = """
                    {
                      "ref": "candidate_python",
                      "label": "Python HR Олег",
                      "systemPrompt": "Ты — Олег, HR-менеджер...",
                      "firstMessageTemplate": "Здравствуйте, {{name}}! Меня зовут Олег...",
                      "secondMessageTemplate": "Отлично! Расскажите о себе — стек, опыт...",
                      "fieldMapping": "{\\"nameField\\":\\"full_name\\",\\"contactField\\":\\"contacts.telegram\\"}",
                      "testTaskUrl": "https://example.com/test",
                      "notificationContact": "@admin"
                    }""")))
    public PersonaDto create(@RequestBody CreatePersonaRequest request) {
        var persona = new Persona();
        persona.setRef(request.ref());
        persona.setLabel(request.label());
        persona.setSystemPrompt(request.systemPrompt());
        persona.setFirstMessageTemplate(request.firstMessageTemplate());
        if (request.secondMessageTemplate() != null) persona.setSecondMessageTemplate(request.secondMessageTemplate());
        if (request.fieldMapping() != null) persona.setFieldMapping(request.fieldMapping());
        if (request.testTaskUrl() != null) persona.setTestTaskUrl(request.testTaskUrl());
        if (request.notificationContact() != null) persona.setNotificationContact(request.notificationContact());
        persona.setActive(true);

        var saved = personaRepository.save(persona);
        return PersonaDto.from(saved);
    }

    @PutMapping("/{ref}")
    @Operation(summary = "Обновить персону", description = "Частичное обновление — передавайте только поля, которые нужно изменить. null-поля не затрагиваются.")
    @ApiResponse(responseCode = "200", description = "Персона обновлена")
    @ApiResponse(responseCode = "404", description = "Персона не найдена")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            content = @Content(examples = @ExampleObject(value = """
                    {
                      "systemPrompt": "Новый системный промпт...",
                      "secondMessageTemplate": "Новый шаблон второго сообщения..."
                    }""")))
    public ResponseEntity<PersonaDto> update(
            @Parameter(description = "Уникальный ref персоны", example = "candidate_java") @PathVariable String ref,
            @RequestBody UpdatePersonaRequest request) {
        return personaRepository.findByRefAndActiveTrue(ref)
                .map(persona -> {
                    if (request.label() != null) persona.setLabel(request.label());
                    if (request.systemPrompt() != null) persona.setSystemPrompt(request.systemPrompt());
                    if (request.firstMessageTemplate() != null) persona.setFirstMessageTemplate(request.firstMessageTemplate());
                    if (request.secondMessageTemplate() != null) persona.setSecondMessageTemplate(request.secondMessageTemplate());
                    if (request.fieldMapping() != null) persona.setFieldMapping(request.fieldMapping());
                    if (request.testTaskUrl() != null) persona.setTestTaskUrl(request.testTaskUrl());
                    if (request.notificationContact() != null) persona.setNotificationContact(request.notificationContact());
                    if (request.active() != null) persona.setActive(request.active());

                    var saved = personaRepository.save(persona);
                    personaService.evictByRef(ref);
                    return ResponseEntity.ok(PersonaDto.from(saved));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{ref}")
    @Operation(summary = "Деактивировать персону", description = "Soft-delete: устанавливает active=false. Персона остаётся в БД, но не используется.")
    @ApiResponse(responseCode = "204", description = "Персона деактивирована")
    @ApiResponse(responseCode = "404", description = "Персона не найдена")
    public ResponseEntity<Void> deactivate(
            @Parameter(description = "Уникальный ref персоны", example = "candidate_java") @PathVariable String ref) {
        return personaRepository.findByRefAndActiveTrue(ref)
                .map(persona -> {
                    persona.setActive(false);
                    personaRepository.save(persona);
                    personaService.evictByRef(ref);
                    return ResponseEntity.noContent().<Void>build();
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @Transactional
    @PatchMapping("/notification-contact")
    @Operation(summary = "Обновить notification-contact для всех персон",
            description = "Массовое обновление контакта для уведомлений у всех активных персон")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            content = @Content(examples = @ExampleObject(value = """
                    {"notificationContact": "@new_admin"}""")))
    public ResponseEntity<BulkUpdateResultDto> updateNotificationContact(
            @RequestBody NotificationContactRequest request) {
        var all = personaRepository.findAllByActiveTrue();
        all.forEach(p -> p.setNotificationContact(request.notificationContact()));
        personaRepository.saveAll(all);
        personaService.evictAll();
        return ResponseEntity.ok(new BulkUpdateResultDto(all.size()));
    }

    @PatchMapping("/{ref}/test-task-url")
    @Operation(summary = "Обновить URL тестового задания", description = "Меняет ссылку на тестовое задание для конкретной персоны")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            content = @Content(examples = @ExampleObject(value = """
                    {"testTaskUrl": "https://github.com/company/test-task"}""")))
    public ResponseEntity<PersonaDto> updateTestTaskUrl(
            @Parameter(description = "Уникальный ref персоны", example = "candidate_java") @PathVariable String ref,
            @RequestBody TestTaskUrlRequest request) {
        return personaRepository.findByRefAndActiveTrue(ref)
                .map(persona -> {
                    persona.setTestTaskUrl(request.testTaskUrl());
                    var saved = personaRepository.save(persona);
                    personaService.evictByRef(ref);
                    return ResponseEntity.ok(PersonaDto.from(saved));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/cache/evict")
    @Operation(summary = "Сбросить кеш персон", description = "Принудительно очищает Spring-кеш всех персон. Полезно после прямых изменений в БД.")
    public ResponseEntity<Void> evictAllCache() {
        personaService.evictAll();
        return ResponseEntity.ok().build();
    }

    public record NotificationContactRequest(
            @Schema(description = "Telegram-контакт для уведомлений", example = "@bubligoom")
            String notificationContact) {}

    public record TestTaskUrlRequest(
            @Schema(description = "URL тестового задания", example = "https://github.com/company/test-task")
            String testTaskUrl) {}

    public record BulkUpdateResultDto(
            @Schema(description = "Количество обновлённых персон", example = "2")
            int updatedCount) {}

    @Schema(description = "Полная информация о персоне")
    public record PersonaDto(
            @Schema(description = "ID в БД", example = "1") Long id,
            @Schema(description = "Уникальный ref", example = "candidate_java") String ref,
            @Schema(description = "Метка/название", example = "Java HR Олег") String label,
            @Schema(description = "Системный промпт для AI-агента") String systemPrompt,
            @Schema(description = "Шаблон первого сообщения (с плейсхолдерами {{name}}, {{reason}})") String firstMessageTemplate,
            @Schema(description = "Шаблон второго сообщения (отправляется без AI при первом ответе кандидата)") String secondMessageTemplate,
            @Schema(description = "JSON-маппинг полей из aiResult") String fieldMapping,
            @Schema(description = "URL тестового задания") String testTaskUrl,
            @Schema(description = "Telegram-контакт для уведомлений") String notificationContact,
            @Schema(description = "Активна ли персона") boolean active) {
        public static PersonaDto from(Persona p) {
            return new PersonaDto(p.getId(), p.getRef(), p.getLabel(),
                    p.getSystemPrompt(), p.getFirstMessageTemplate(), p.getSecondMessageTemplate(),
                    p.getFieldMapping(), p.getTestTaskUrl(), p.getNotificationContact(), p.isActive());
        }
    }

    @Schema(description = "Запрос на создание персоны")
    public record CreatePersonaRequest(
            @Schema(description = "Уникальный ref", example = "candidate_python", requiredMode = Schema.RequiredMode.REQUIRED) String ref,
            @Schema(description = "Метка/название", example = "Python HR Олег") String label,
            @Schema(description = "Системный промпт", requiredMode = Schema.RequiredMode.REQUIRED) String systemPrompt,
            @Schema(description = "Шаблон первого сообщения", requiredMode = Schema.RequiredMode.REQUIRED) String firstMessageTemplate,
            @Schema(description = "Шаблон второго сообщения") String secondMessageTemplate,
            @Schema(description = "JSON-маппинг полей") String fieldMapping,
            @Schema(description = "URL тестового задания") String testTaskUrl,
            @Schema(description = "Telegram-контакт для уведомлений") String notificationContact) {}

    @Schema(description = "Запрос на обновление персоны (все поля опциональные)")
    public record UpdatePersonaRequest(
            @Schema(description = "Метка/название") String label,
            @Schema(description = "Системный промпт") String systemPrompt,
            @Schema(description = "Шаблон первого сообщения") String firstMessageTemplate,
            @Schema(description = "Шаблон второго сообщения") String secondMessageTemplate,
            @Schema(description = "JSON-маппинг полей") String fieldMapping,
            @Schema(description = "URL тестового задания") String testTaskUrl,
            @Schema(description = "Telegram-контакт для уведомлений") String notificationContact,
            @Schema(description = "Активна ли персона") Boolean active) {}
}
