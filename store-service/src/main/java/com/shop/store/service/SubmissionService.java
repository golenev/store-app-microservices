package com.shop.store.service;

import com.shop.store.codec.ShopCodec;
import com.shop.store.dto.Submission;
import com.shop.store.dto.SubmitInput;
import com.shop.store.exception.ShopException;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import java.util.UUID;

/** Координирует оформление без собственной транзакции, чтобы восстановление повтора происходило после отката конфликта UNIQUE. */
@Service
public class SubmissionService {
    private final SubmissionTransactionService store;
    private final ShopCodec codec;
    /**
     * Получает зависимости слоя без выполнения внешних операций; параметры сохраняются для последующих вызовов.
     *
     * @param store сервис транзакций оформления и outbox
     * @param codec строгий разбор и сериализация протокола
     */
    public SubmissionService(SubmissionTransactionService store,ShopCodec codec) { this.store=store; this.codec=codec; }
    /**
     * Проверяет ключ key и input, принимает заявку после фиксации транзакции. Конфликт UNIQUE сначала
     * откатывается, затем победивший результат читается в новой транзакции; другой отпечаток вызывает 409.
     *
     * @param scope идентификатор магазина
     * @param cart UUID корзины
     * @param key регистрозависимый ключ операции внутри магазина
     * @param input проверяемые параметры операции
     */
    public Submission submit(String scope,UUID cart,String key,SubmitInput input) {
        codec.identifier(scope); codec.idempotencyKey(key);
        if(input==null || input.expectedCartVersion()<0 || input.expectedCartVersion()>ShopCodec.MAX_VERSION)
            throw new ShopException(400,"VALIDATION_ERROR","Invalid expectedCartVersion");
        String fingerprint=codec.submissionFingerprint(scope,cart,input.expectedCartVersion());
        try { return store.accept(scope,cart,key,input.expectedCartVersion(),fingerprint); }
        catch(DuplicateKeyException conflict) {
            return store.replay(scope,key,fingerprint).orElseThrow(() -> conflict);
        }
    }
}
