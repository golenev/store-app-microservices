package com.shop.store.repository.jpa;

import com.shop.store.entity.ProcessedEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

/** Читает отметку обработки по неизменяемому eventId стандартными операциями Spring Data JPA. */
public interface ProcessedEventJpaRepository extends JpaRepository<ProcessedEventEntity, UUID> { }
