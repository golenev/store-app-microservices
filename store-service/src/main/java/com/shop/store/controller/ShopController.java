package com.shop.store.controller;

import com.shop.store.codec.ShopCodec;
import com.shop.store.dto.Cart;
import com.shop.store.dto.Catalog;
import com.shop.store.dto.Submission;
import com.shop.store.exception.ShopException;
import com.shop.store.service.CartService;
import com.shop.store.service.SubmissionService;
import com.shop.store.service.SubmissionTransactionService;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.net.URI;

/** Обслуживает HTTP-контракт каталога, корзин и оформления в пределах магазина. */
@RestController
@RequestMapping("/stores/{storeId}")
public class ShopController {
    private final CartService carts;
    private final ShopCodec codec;
    private final SubmissionService submissions;
    private final SubmissionTransactionService submissionStore;
    /**
     * Получает зависимости слоя без выполнения внешних операций; параметры сохраняются для последующих вызовов.
     *
     * @param carts сервис независимых корзин
     * @param codec строгий разбор и сериализация протокола
     * @param submissions фасад идемпотентного оформления
     * @param submissionStore сервис транзакций оформления и outbox
     */
    public ShopController(CartService carts,ShopCodec codec,SubmissionService submissions,SubmissionTransactionService submissionStore) {
        this.carts=carts; this.codec=codec; this.submissions=submissions; this.submissionStore=submissionStore;
    }
    /**
     * Принимает raw и ключ key для cartId магазина storeId; возвращает 202 и Location после атомарного
     * оформления либо идемпотентного повтора.
     *
     * @param storeId идентификатор магазина
     * @param cartId идентификатор корзины из HTTP-маршрута
     * @param key регистрозависимый ключ операции внутри магазина
     * @param raw исходный JSON без изменения содержимого и идентификаторов
     * @return HTTP-ответ с описанным статусом и телом
     */
    @PostMapping(value="/carts/{cartId}/submit",consumes=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Submission> submit(@PathVariable String storeId,@PathVariable String cartId,
                                             @RequestHeader(value="Idempotency-Key",required=false) String key,@RequestBody String raw) {
        Submission result=submissions.submit(codec.identifier(storeId),codec.uuid(cartId),key,codec.submit(raw));
        return ResponseEntity.accepted().location(URI.create("/stores/"+result.storeId()+"/submissions/"+result.submissionId())).body(result);
    }
    /**
     * Возвращает состояние операции submissionId магазина storeId без повторного оформления или запуска
     * отправителя.
     *
     * @param storeId идентификатор магазина
     * @param submissionId идентификатор заявки из HTTP-маршрута
     */
    @GetMapping("/submissions/{submissionId}")
    public Submission submission(@PathVariable String storeId,@PathVariable String submissionId) {
        return submissionStore.view(codec.identifier(storeId),codec.uuid(submissionId));
    }
    /**
     * Возвращает каталог storeId: одна позиция на продукт, включая нулевой остаток и последнюю применённую
     * цену.
     *
     * @param storeId идентификатор магазина
     */
    @GetMapping("/catalog")
    public Catalog catalog(@PathVariable String storeId) { return carts.catalog(codec.identifier(storeId)); }
    /**
     * Создаёт независимую корзину storeId и возвращает 201 с Location; непустое raw отклоняет, чтобы клиент не
     * назначал состав или цены.
     *
     * @param storeId идентификатор магазина
     * @param raw исходный JSON без изменения содержимого и идентификаторов
     * @return HTTP-ответ с описанным статусом и телом
     */
    @PostMapping("/carts")
    public ResponseEntity<Cart> create(@PathVariable String storeId,@RequestBody(required=false) String raw) {
        if(raw!=null && !raw.isBlank()) throw new ShopException(400,"VALIDATION_ERROR","Cart creation does not accept a body");
        Cart cart=carts.create(codec.identifier(storeId));
        return ResponseEntity.created(URI.create("/stores/"+cart.storeId()+"/carts/"+cart.cartId())).body(cart);
    }
    /**
     * Возвращает корзину cartId магазина storeId с согласованными составом, версией и ценами.
     *
     * @param storeId идентификатор магазина
     * @param cartId идентификатор корзины из HTTP-маршрута
     */
    @GetMapping("/carts/{cartId}")
    public Cart get(@PathVariable String storeId,@PathVariable String cartId) {
        return carts.get(codec.identifier(storeId),codec.uuid(cartId));
    }
    /**
     * Заменяет итоговое количество stockItemId из raw с проверкой версии и остатка; возвращает результат
     * зафиксированной операции.
     *
     * @param storeId идентификатор магазина
     * @param cartId идентификатор корзины из HTTP-маршрута
     * @param stockItemId идентификатор позиции из HTTP-маршрута
     * @param raw исходный JSON без изменения содержимого и идентификаторов
     */
    @PutMapping(value="/carts/{cartId}/items/{stockItemId}",consumes=MediaType.APPLICATION_JSON_VALUE)
    public Cart put(@PathVariable String storeId,@PathVariable String cartId,@PathVariable String stockItemId,@RequestBody String raw) {
        return carts.put(codec.identifier(storeId),codec.uuid(cartId),codec.uuid(stockItemId),codec.put(raw));
    }
    /**
     * Удаляет существующую позицию stockItemId с обязательной expectedCartVersion; успешная операция
     * увеличивает версию один раз.
     *
     * @param storeId идентификатор магазина
     * @param cartId идентификатор корзины из HTTP-маршрута
     * @param stockItemId идентификатор позиции из HTTP-маршрута
     * @param expectedCartVersion ожидаемая версия корзины
     */
    @DeleteMapping("/carts/{cartId}/items/{stockItemId}")
    public Cart delete(@PathVariable String storeId,@PathVariable String cartId,@PathVariable String stockItemId,
                       @RequestParam String expectedCartVersion) {
        return carts.delete(codec.identifier(storeId),codec.uuid(cartId),codec.uuid(stockItemId),codec.version(expectedCartVersion));
    }
}
