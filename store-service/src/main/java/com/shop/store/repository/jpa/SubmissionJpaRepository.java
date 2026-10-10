package com.shop.store.repository.jpa;

import com.shop.store.dto.Submission;
import com.shop.store.entity.SubmissionEntity;
import com.shop.store.model.SubmissionMatch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;
import java.util.UUID;

/** Возвращает снимки оформления и типизированные JPQL-проекции принятой заявки. */
public interface SubmissionJpaRepository extends JpaRepository<SubmissionEntity, UUID> {
    /**
     * Читает запрос по ключу повтора внутри магазина; наружу сущность не передаётся.
     * @param store магазин
     * @param key ключ повтора
     * @return совпадение или пустой список
     */
    @Query("select new com.shop.store.model.SubmissionMatch(s.submissionId, s.requestFingerprint) from SubmissionEntity s where s.storeId = :store and s.idempotencyKey = :key")
    List<SubmissionMatch> findMatch(String store, String key);

    /**
     * Читает принятый снимок корзины.
     * @param store магазин
     * @param cart корзина
     * @return снимок или пустой список
     */
    @Query("select s.cartSnapshot from SubmissionEntity s where s.storeId = :store and s.cartId = :cart")
    List<String> snapshots(String store, UUID cart);

    /**
     * Соединяет принятие и публикацию одной выборкой без загрузки графа сущностей.
     * @param store магазин
     * @param id заявка
     * @return публичный DTO заявки или пустой список
     */
    @Query("""
            select new com.shop.store.dto.Submission(s.storeId, s.submissionId, s.cartId, s.eventId,
                o.publicationStatus, s.acceptedAt, o.publishedAt)
            from SubmissionEntity s, OutboxEntity o
            where o.submissionId = s.submissionId and s.storeId = :store and s.submissionId = :id
            """)
    List<Submission> views(String store, UUID id);
}
