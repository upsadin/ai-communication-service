package com.aicomm.persona;

import com.aicomm.domain.Persona;
import com.aicomm.repository.PersonaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Provides persona lookup by ref with caching.
 * Personas define the AI agent's behavior (system prompt) for each ref.
 *
 * Cache allows prompt changes in DB to take effect without redeploy —
 * just evict the cache via evictByRef() or evictAll().
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PersonaService {

    private final PersonaRepository personaRepository;

    @Cacheable(value = "personas", key = "#ref")
    public Optional<Persona> getByRef(String ref) {
        log.debug("Loading persona from DB: ref={}", ref);
        return personaRepository.findByRefAndActiveTrue(ref);
    }

    @CacheEvict(value = "personas", key = "#ref")
    public void evictByRef(String ref) {
        log.info("Evicted persona cache for ref={}", ref);
    }

    @CacheEvict(value = "personas", allEntries = true)
    public void evictAll() {
        log.info("Evicted all persona cache entries");
    }
}
