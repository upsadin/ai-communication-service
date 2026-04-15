package com.aicomm.controller;

import com.aicomm.domain.Persona;
import com.aicomm.persona.PersonaService;
import com.aicomm.repository.PersonaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PersonaAdminControllerTest {

    @Mock private PersonaRepository personaRepository;
    @Mock private PersonaService personaService;

    @InjectMocks
    private PersonaAdminController controller;

    private Persona persona;

    @BeforeEach
    void setUp() {
        persona = new Persona();
        persona.setId(1L);
        persona.setRef("candidate_java");
        persona.setLabel("Java HR");
        persona.setSystemPrompt("Ты — Анна");
        persona.setFirstMessageTemplate("Привет, {{name}}!");
        persona.setActive(true);
    }

    @Test
    void listAll_returnsMappedDtos() {
        when(personaRepository.findAll()).thenReturn(List.of(persona));

        var result = controller.listAll();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).ref()).isEqualTo("candidate_java");
        assertThat(result.get(0).label()).isEqualTo("Java HR");
    }

    @Test
    void getByRef_returnsPersona() {
        when(personaRepository.findByRefAndActiveTrue("candidate_java")).thenReturn(Optional.of(persona));

        var response = controller.getByRef("candidate_java");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().ref()).isEqualTo("candidate_java");
    }

    @Test
    void getByRef_returns404_whenNotFound() {
        when(personaRepository.findByRefAndActiveTrue("unknown")).thenReturn(Optional.empty());

        var response = controller.getByRef("unknown");

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void create_savesAndReturnsDto() {
        when(personaRepository.save(any(Persona.class))).thenAnswer(inv -> {
            var p = inv.<Persona>getArgument(0);
            p.setId(2L);
            return p;
        });

        var request = new PersonaAdminController.CreatePersonaRequest(
                "candidate_go", "Go HR", "Ты — Анна для Go", "Привет, {{name}}!", "Отлично! Расскажите...", "Стек: Go, PostgreSQL", "{}", "https://test.com", "@bubligoom");

        var result = controller.create(request);

        assertThat(result.id()).isEqualTo(2L);
        assertThat(result.ref()).isEqualTo("candidate_go");
        assertThat(result.systemPrompt()).isEqualTo("Ты — Анна для Go");
    }

    @Test
    void update_changesFieldsAndEvictsCache() {
        when(personaRepository.findByRefAndActiveTrue("candidate_java")).thenReturn(Optional.of(persona));
        when(personaRepository.save(any(Persona.class))).thenAnswer(inv -> inv.getArgument(0));

        var request = new PersonaAdminController.UpdatePersonaRequest(
                "New Label", "Новый промпт", null, null, null, null, null, null, null);

        var response = controller.update("candidate_java", request);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().label()).isEqualTo("New Label");
        assertThat(response.getBody().systemPrompt()).isEqualTo("Новый промпт");
        assertThat(response.getBody().firstMessageTemplate()).isEqualTo("Привет, {{name}}!");

        verify(personaService).evictByRef("candidate_java");
    }

    @Test
    void update_skipsNullFields() {
        when(personaRepository.findByRefAndActiveTrue("candidate_java")).thenReturn(Optional.of(persona));
        when(personaRepository.save(any(Persona.class))).thenAnswer(inv -> inv.getArgument(0));

        var request = new PersonaAdminController.UpdatePersonaRequest(null, null, null, null, null, null, null, null, null);

        var response = controller.update("candidate_java", request);

        assertThat(response.getBody().label()).isEqualTo("Java HR");
        assertThat(response.getBody().systemPrompt()).isEqualTo("Ты — Анна");
    }

    @Test
    void update_returns404_whenNotFound() {
        when(personaRepository.findByRefAndActiveTrue("unknown")).thenReturn(Optional.empty());

        var response = controller.update("unknown",
                new PersonaAdminController.UpdatePersonaRequest("x", "x", "x", null, null, null, null, null, null));

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void deactivate_setsInactiveAndEvictsCache() {
        when(personaRepository.findByRefAndActiveTrue("candidate_java")).thenReturn(Optional.of(persona));

        var response = controller.deactivate("candidate_java");

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        assertThat(persona.isActive()).isFalse();
        verify(personaRepository).save(persona);
        verify(personaService).evictByRef("candidate_java");
    }

    @Test
    void deactivate_returns404_whenNotFound() {
        when(personaRepository.findByRefAndActiveTrue("unknown")).thenReturn(Optional.empty());

        var response = controller.deactivate("unknown");

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void evictAllCache_callsService() {
        var response = controller.evictAllCache();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(personaService).evictAll();
    }
}
