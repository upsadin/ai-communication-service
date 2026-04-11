package com.aicomm.repository;

import com.aicomm.domain.Persona;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PersonaRepository extends JpaRepository<Persona, Long> {

    Optional<Persona> findByRefAndActiveTrue(String ref);

    List<Persona> findAllByActiveTrue();
}
