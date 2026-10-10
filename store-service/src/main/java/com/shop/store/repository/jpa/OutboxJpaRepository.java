package com.shop.store.repository.jpa;

import com.shop.store.entity.OutboxEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Захватывает исходящие события PostgreSQL и обновляет публикацию с проверкой токена владельца. */
public interface OutboxJpaRepository extends JpaRepository<OutboxEntity, UUID> {
    /**
     * Пропускает чужие блокировки и выбирает одно готовое событие. Native SQL сохраняет SKIP LOCKED;
     * вызывающий сервис держит транзакцию до записи токена, но не до отправки Kafka.
     * @param dueAt время проверки готовности
     * @param expiredAt время проверки истечения владения
     * @return одно управляемое событие или пустой список
     */
    @Query(value = """
            SELECT * FROM store_outbox WHERE publication_status='PENDING'
            AND next_attempt_at<=:dueAt AND (lease_until IS NULL OR lease_until<=:expiredAt)
            ORDER BY next_attempt_at,event_id LIMIT 1 FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEntity> lockDue(Instant dueAt, Instant expiredAt);

    /**
     * Фиксирует публикацию только текущего владельца. Перед bulk UPDATE сбрасывает изменения,
     * после него очищает контекст, чтобы уже загруженные сущности не оставались устаревшими.
     * @param publishedAt подтверждение Kafka
     * @param event событие
     * @param token токен владельца
     * @return 1 при изменении, иначе 0
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update OutboxEntity o set o.publicationStatus = 'PUBLISHED', o.publishedAt = :publishedAt,
                o.leaseToken = null, o.leaseUntil = null, o.lastError = null
            where o.eventId = :event and o.publicationStatus = 'PENDING' and o.leaseToken = :token
            """)
    int published(Instant publishedAt, UUID event, UUID token);

    /**
     * Назначает повтор только текущему владельцу, освобождает токен и очищает контекст после bulk UPDATE.
     * @param nextAttemptAt время повтора
     * @param event событие
     * @param token токен владельца
     * @return 1 при изменении, иначе 0
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update OutboxEntity o set o.nextAttemptAt = :nextAttemptAt,
                o.lastError = 'Kafka acknowledgement unavailable', o.leaseToken = null, o.leaseUntil = null
            where o.eventId = :event and o.publicationStatus = 'PENDING' and o.leaseToken = :token
            """)
    int retry(Instant nextAttemptAt, UUID event, UUID token);
}
