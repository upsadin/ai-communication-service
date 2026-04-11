package com.aicomm.controller;

import com.aicomm.domain.Persona;
import com.aicomm.persona.PersonaService;
import com.aicomm.repository.PersonaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Admin API for managing Personas.
 * Allows CRUD operations and prompt editing without redeploy.
 * All endpoints under /internal/admin/personas.
 */
@RestController
@RequestMapping("/internal/admin/personas")
@RequiredArgsConstructor
public class PersonaAdminController {

    private final PersonaRepository personaRepository;
    private final PersonaService personaService;

    @GetMapping
    public List<PersonaDto> listAll() {
        return personaRepository.findAll().stream()
                .map(PersonaDto::from)
                .toList();
    }

    @GetMapping("/{ref}")
    public ResponseEntity<PersonaDto> getByRef(@PathVariable String ref) {
        return personaRepository.findByRefAndActiveTrue(ref)
                .map(PersonaDto::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public PersonaDto create(@RequestBody CreatePersonaRequest request) {
        var persona = new Persona();
        persona.setRef(request.ref());
        persona.setLabel(request.label());
        persona.setSystemPrompt(request.systemPrompt());
        persona.setFirstMessageTemplate(request.firstMessageTemplate());
        if (request.fieldMapping() != null) persona.setFieldMapping(request.fieldMapping());
        if (request.testTaskUrl() != null) persona.setTestTaskUrl(request.testTaskUrl());
        if (request.notificationContact() != null) persona.setNotificationContact(request.notificationContact());
        persona.setActive(true);

        var saved = personaRepository.save(persona);
        return PersonaDto.from(saved);
    }

    @PutMapping("/{ref}")
    public ResponseEntity<PersonaDto> update(@PathVariable String ref, @RequestBody UpdatePersonaRequest request) {
        return personaRepository.findByRefAndActiveTrue(ref)
                .map(persona -> {
                    if (request.label() != null) persona.setLabel(request.label());
                    if (request.systemPrompt() != null) persona.setSystemPrompt(request.systemPrompt());
                    if (request.firstMessageTemplate() != null) persona.setFirstMessageTemplate(request.firstMessageTemplate());
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
    public ResponseEntity<Void> deactivate(@PathVariable String ref) {
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
    public ResponseEntity<BulkUpdateResultDto> updateNotificationContact(
            @RequestBody NotificationContactRequest request) {
        var all = personaRepository.findAllByActiveTrue();
        all.forEach(p -> p.setNotificationContact(request.notificationContact()));
        personaRepository.saveAll(all);
        personaService.evictAll();
        return ResponseEntity.ok(new BulkUpdateResultDto(all.size()));
    }

    @PatchMapping("/{ref}/test-task-url")
    public ResponseEntity<PersonaDto> updateTestTaskUrl(
            @PathVariable String ref,
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
    public ResponseEntity<Void> evictAllCache() {
        personaService.evictAll();
        return ResponseEntity.ok().build();
    }

    public record NotificationContactRequest(String notificationContact) {}
    public record TestTaskUrlRequest(String testTaskUrl) {}
    public record BulkUpdateResultDto(int updatedCount) {}

    public record PersonaDto(Long id, String ref, String label, String systemPrompt,
                             String firstMessageTemplate, String fieldMapping,
                             String testTaskUrl, String notificationContact,
                             boolean active) {
        public static PersonaDto from(Persona p) {
            return new PersonaDto(p.getId(), p.getRef(), p.getLabel(),
                    p.getSystemPrompt(), p.getFirstMessageTemplate(), p.getFieldMapping(),
                    p.getTestTaskUrl(), p.getNotificationContact(), p.isActive());
        }
    }

    public record CreatePersonaRequest(String ref, String label, String systemPrompt,
                                       String firstMessageTemplate, String fieldMapping,
                                       String testTaskUrl, String notificationContact) {}

    public record UpdatePersonaRequest(String label, String systemPrompt, String firstMessageTemplate,
                                       String fieldMapping, String testTaskUrl, String notificationContact,
                                       Boolean active) {}
}
