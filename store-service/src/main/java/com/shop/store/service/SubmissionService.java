package com.shop.store.service;

import com.shop.store.codec.ShopCodec;
import com.shop.store.dto.Submission;
import com.shop.store.dto.SubmitInput;
import com.shop.store.exception.ShopException;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import java.util.UUID;

/**
 * Оформляет корзину и восстанавливает результат одновременного повтора запроса. Работает без собственной
 * транзакции, чтобы читать принятый результат после отмены конфликтующей записи.
 */
@Service
public class SubmissionService {
    private final SubmissionTransactionService store;
    private final ShopCodec codec;
    /**
     * Подключает сервис транзакций оформления и проверку входных данных.
     *
     * @param store транзакции оформления и операции с очередью событий
     * @param codec проверка входного JSON магазина и преобразование его моделей
     */
    public SubmissionService(SubmissionTransactionService store,ShopCodec codec) { this.store=store; this.codec=codec; }
    /**
     * Проверяет ключ повтора и ожидаемую версию, затем оформляет корзину в отдельной транзакции. Если
     * одновременный запрос уже занял тот же ключ, дожидается отмены своей записи и читает принятый результат в
     * новой транзакции. При совпадении данных возвращает прежнюю операцию без нового списания; другой запрос с
     * тем же ключом вызывает {@code IDEMPOTENCY_KEY_REUSED} и HTTP 409.
     *
     * @param scope идентификатор магазина
     * @param cart UUID корзины
     * @param key ключ распознавания повторного оформления внутри магазина
     * @param input ожидаемая версия оформляемой корзины
     * @return принятая заявка и состояние отправки её события
     */
    public Submission submit(String scope,UUID cart,String key,SubmitInput input) {
        codec.identifier(scope); codec.idempotencyKey(key);
        if(input==null || input.expectedCartVersion()<0 || input.expectedCartVersion()>ShopCodec.MAX_VERSION)
            throw new ShopException(400,"VALIDATION_ERROR","Invalid expectedCartVersion");
        String fingerprint=codec.submissionFingerprint(scope,cart,input.expectedCartVersion());
        try { return store.accept(scope,cart,key,input.expectedCartVersion(),fingerprint); }
        catch(DataIntegrityViolationException conflict) {
            if (!uniqueConflict(conflict)) throw conflict;
            return store.replay(scope,key,fingerprint).orElseThrow(() -> conflict);
        }
    }

    /**
     * Распознаёт только нарушение UNIQUE PostgreSQL в цепочке исключений Hibernate/Spring.
     * NOT NULL, CHECK и внешние ключи не подменяет повтором запроса; их ошибка передаётся вызывающему коду.
     * @param failure ошибка сохранения JPA
     * @return true только для SQLSTATE 23505
     */
    private boolean uniqueConflict(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.sql.SQLException sql && "23505".equals(sql.getSQLState())) return true;
        }
        return false;
    }
}
