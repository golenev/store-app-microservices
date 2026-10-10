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

/**
 * Принимает HTTP-запросы каталога, корзин и оформления в пределах указанного магазина.
 */
@RestController
@RequestMapping("/stores/{storeId}")
public class ShopController {
    private final CartService carts;
    private final ShopCodec codec;
    private final SubmissionService submissions;
    private final SubmissionTransactionService submissionStore;
    /**
     * Подключает операции с корзинами, проверку запросов, оформление и чтение принятых заявок.
     *
     * @param carts создание, чтение и изменение корзин
     * @param codec проверка входного JSON магазина и преобразование его моделей
     * @param submissions оформление и восстановление результата повторного запроса
     * @param submissionStore транзакции оформления и операции с очередью событий
     */
    public ShopController(CartService carts,ShopCodec codec,SubmissionService submissions,SubmissionTransactionService submissionStore) {
        this.carts=carts; this.codec=codec; this.submissions=submissions; this.submissionStore=submissionStore;
    }
    /**
     * Оформляет корзину по ожидаемой версии и ключу повтора. После сохранения всех изменений возвращает HTTP
     * 202, принятую операцию и её адрес в {@code Location}. Повтор того же запроса возвращает ту же операцию
     * без нового списания.
     *
     * @param storeId идентификатор магазина
     * @param cartId идентификатор корзины
     * @param key ключ распознавания повторного оформления из заголовка {@code Idempotency-Key}
     * @param raw JSON с ожидаемой версией оформляемой корзины
     * @return HTTP 202 с принятой заявкой и её адресом в {@code Location}
     */
    @PostMapping(value="/carts/{cartId}/submit",consumes=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Submission> submit(@PathVariable String storeId,@PathVariable String cartId,
                                             @RequestHeader(value="Idempotency-Key",required=false) String key,@RequestBody String raw) {
        Submission result=submissions.submit(codec.identifier(storeId),codec.uuid(cartId),key,codec.submit(raw));
        return ResponseEntity.accepted().location(URI.create("/stores/"+result.storeId()+"/submissions/"+result.submissionId())).body(result);
    }
    /**
     * Возвращает состояние принятой операции указанного магазина. Чтение не оформляет корзину повторно и не
     * запускает отправку в Kafka.
     *
     * @param storeId идентификатор магазина
     * @param submissionId идентификатор принятой заявки
     * @return принятая заявка и состояние отправки её события
     */
    @GetMapping("/submissions/{submissionId}")
    public Submission submission(@PathVariable String storeId,@PathVariable String submissionId) {
        return submissionStore.view(codec.identifier(storeId),codec.uuid(submissionId));
    }
    /**
     * Возвращает каталог магазина: одну позицию на продукт с доступным количеством и последней применённой
     * ценой. Товары с нулевым остатком также входят в ответ.
     *
     * @param storeId идентификатор магазина
     * @return каталог указанного магазина с текущими ценами и остатками
     */
    @GetMapping("/catalog")
    public Catalog catalog(@PathVariable String storeId) { return carts.catalog(codec.identifier(storeId)); }
    /**
     * Создаёт отдельную пустую корзину и возвращает HTTP 201 с её состоянием и адресом в {@code Location}.
     * Непустое тело запроса отклоняет с {@code VALIDATION_ERROR}: начальные поля назначает сервер.
     *
     * @param storeId идентификатор магазина
     * @param raw тело запроса создания; должно отсутствовать или содержать только пробелы
     * @return HTTP 201 с новой корзиной и её адресом в {@code Location}
     */
    @PostMapping("/carts")
    public ResponseEntity<Cart> create(@PathVariable String storeId,@RequestBody(required=false) String raw) {
        if(raw!=null && !raw.isBlank()) throw new ShopException(400,"VALIDATION_ERROR","Cart creation does not accept a body");
        Cart cart=carts.create(codec.identifier(storeId));
        return ResponseEntity.created(URI.create("/stores/"+cart.storeId()+"/carts/"+cart.cartId())).body(cart);
    }
    /**
     * Возвращает состав, версию, цены и сумму корзины указанного магазина. Идентификаторы проверяет до вызова
     * сервиса.
     *
     * @param storeId идентификатор магазина
     * @param cartId идентификатор корзины
     * @return состояние корзины с позицией каждой строки, версией и итоговой суммой
     */
    @GetMapping("/carts/{cartId}")
    public Cart get(@PathVariable String storeId,@PathVariable String cartId) {
        return carts.get(codec.identifier(storeId),codec.uuid(cartId));
    }
    /**
     * Устанавливает итоговое количество товара из тела запроса. Проверяет ожидаемую версию и доступный
     * остаток; возвращает корзину после сохранения изменения. Переданное количество заменяет прежнее, а не
     * прибавляется к нему.
     *
     * @param storeId идентификатор магазина
     * @param cartId идентификатор корзины
     * @param stockItemId идентификатор позиции остатка магазина
     * @param raw JSON с новым итоговым количеством и ожидаемой версией корзины
     * @return состояние корзины с позицией каждой строки, версией и итоговой суммой
     */
    @PutMapping(value="/carts/{cartId}/items/{stockItemId}",consumes=MediaType.APPLICATION_JSON_VALUE)
    public Cart put(@PathVariable String storeId,@PathVariable String cartId,@PathVariable String stockItemId,@RequestBody String raw) {
        return carts.put(codec.identifier(storeId),codec.uuid(cartId),codec.uuid(stockItemId),codec.put(raw));
    }
    /**
     * Удаляет существующую позицию с проверкой обязательной ожидаемой версии корзины. Возвращает обновлённую
     * корзину; успешное удаление увеличивает её версию на один.
     *
     * @param storeId идентификатор магазина
     * @param cartId идентификатор корзины
     * @param stockItemId идентификатор позиции остатка магазина
     * @param expectedCartVersion версия корзины, которую клиент ожидает перед изменением
     * @return состояние корзины с позицией каждой строки, версией и итоговой суммой
     */
    @DeleteMapping("/carts/{cartId}/items/{stockItemId}")
    public Cart delete(@PathVariable String storeId,@PathVariable String cartId,@PathVariable String stockItemId,
                       @RequestParam String expectedCartVersion) {
        return carts.delete(codec.identifier(storeId),codec.uuid(cartId),codec.uuid(stockItemId),codec.version(expectedCartVersion));
    }
}
