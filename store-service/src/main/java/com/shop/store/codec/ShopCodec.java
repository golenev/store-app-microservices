package com.shop.store.codec;

import com.shop.store.dto.Cart;
import com.shop.store.dto.PutItem;
import com.shop.store.dto.SubmitInput;
import com.shop.store.exception.ShopException;
import com.shop.store.messaging.dto.GoodsEvent;
import com.shop.store.messaging.dto.PostedLine;
import com.shop.store.messaging.dto.PostedPayload;
import com.shop.store.model.Decoded;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Проверяет JSON-запросы и события магазина и переводит их в Java-объекты. Цены и суммы обрабатывает как
 * точные десятичные значения.
 */
@Component
public class ShopCodec {
    public static final long MAX_VERSION = 9007199254740991L;
    private final ObjectMapper mapper;
    private final ShopJsonReader reader;
    private final ShopInputValidator validator;
    /**
     * Подключает строгое чтение DTO и проверку контракта. Общий mapper приложения не меняет;
     * сериализацию сохранённых моделей оставляет отдельной от чтения входного протокола.
     *
     * @param mapper настройки преобразования Java-объектов и JSON
     * @param validator проверка ограничений DTO и связанных условий поставки
     */
    public ShopCodec(ObjectMapper mapper, ShopInputValidator validator) {
        this.reader = new ShopJsonReader(mapper);
        this.validator = validator;
        this.mapper = mapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }
    /**
     * Читает событие оприходования {@code GoodsPosted} и проверяет строки, цены и даты. Приводит наценку к
     * шести знакам после точки и сортирует строки по {@code lineId}. Возвращает событие и контрольную сумму
     * содержимого поставки для сравнения повторов; неверные данные отклоняет с {@code VALIDATION_ERROR}.
     *
     * @param raw JSON-событие {@code GoodsPosted} из Kafka
     * @return проверенное событие и контрольная сумма содержимого поставки
     */
    public Decoded goods(String raw) {
        GoodsEvent event = reader.read(raw, GoodsEvent.class);
        validator.goods(event);
        GoodsEvent normalized = normalize(event);
        return new Decoded(normalized, fingerprint(normalized.payload()));
    }

    /**
     * Создаёт неизменяемое событие с шестью знаками наценки и строками в порядке lineId.
     * Исходный DTO не меняет; нормализация сохраняет прежнюю контрольную сумму повторов.
     * @param event проверенное событие
     * @return событие с нормализованным содержимым
     */
    private GoodsEvent normalize(GoodsEvent event) {
        List<PostedLine> lines = event.payload().items().stream()
                .map(line -> new PostedLine(line.lineId(), line.productId(), line.productType(),
                        line.shortName(), line.description(), line.quantity(), line.purchasePrice(),
                        line.currency(), new BigDecimal(line.markupRate()).setScale(6).toPlainString(),
                        line.tariffRuleId(), line.tariffVersion(), line.salePrice()))
                .sorted(Comparator.comparing(PostedLine::lineId))
                .toList();
        PostedPayload payload = event.payload();
        PostedPayload normalized = new PostedPayload(payload.deliveryId(), payload.deliverySequence(),
                payload.receivedAt(), payload.postedAt(), lines);
        return new GoodsEvent(event.eventId(), event.eventType(), event.schemaVersion(),
                event.occurredAt(), event.storeId(), normalized);
    }

    /**
     * Читает новое итоговое количество товара и ожидаемую версию корзины. Разрешает только эти два поля;
     * неверные значения или лишние поля отклоняет с {@code VALIDATION_ERROR}.
     *
     * @param raw JSON с полями {@code quantity} и {@code expectedCartVersion}
     * @return проверенное количество товара и ожидаемая версия корзины
     */
    public PutItem put(String raw) {
        PutItem input = reader.read(raw, PutItem.class);
        validator.validate(input);
        return input;
    }
    /**
     * Читает ожидаемую версию корзины для оформления. Состав корзины и цены клиент передать не может:
     * дополнительные поля отклоняются.
     *
     * @param raw JSON с единственным полем {@code expectedCartVersion}
     * @return проверенная ожидаемая версия оформления
     */
    public SubmitInput submit(String raw) {
        SubmitInput input = reader.read(raw, SubmitInput.class);
        validator.validate(input);
        return input;
    }
    /**
     * Проверяет ключ, по которому магазин распознаёт повтор оформления. Допускает от 1 до 128 латинских букв,
     * цифр и символов {@code . _ : -}; регистр сохраняет. При нарушении формата выдаёт {@code
     * VALIDATION_ERROR}.
     *
     * @param value ключ повтора оформления из заголовка {@code Idempotency-Key}
     * @return проверенный ключ повтора без изменения регистра и символов
     */
    public String idempotencyKey(String value) {
        require(value != null && value.matches("[A-Za-z0-9._:-]{1,128}"), "Invalid Idempotency-Key");
        return value;
    }
    /**
     * Вычисляет контрольную сумму SHA-256 магазина, корзины и ожидаемой версии. По ней можно отличить повтор
     * того же запроса от другого запроса с прежним ключом; цены и состав корзины в расчёт не входят.
     *
     * @param store идентификатор магазина
     * @param cart UUID корзины
     * @param version версия корзины
     * @return контрольная сумма параметров оформления в шестнадцатеричной записи
     */
    public String submissionFingerprint(String store, UUID cart, long version) {
        return hash(json(List.of(store, cart.toString(), version)));
    }
    /**
     * Читает сохранённый при оформлении снимок корзины. Если JSON нельзя восстановить, выдаёт {@code
     * DEPENDENCY_UNAVAILABLE}, поскольку принятый заказ нельзя достоверно показать клиенту.
     *
     * @param raw JSON-снимок оформленной корзины из БД
     * @return состояние корзины с позицией каждой строки, версией и итоговой суммой
     */
    public Cart cartSnapshot(String raw) {
        try {
            return mapper.readValue(raw, Cart.class);
        } catch (Exception failure) {
            throw new ShopException(503, "DEPENDENCY_UNAVAILABLE", "Cart snapshot unavailable");
        }
    }
    /**
     * Преобразует строку версии в целое число от 0 до {@code MAX_VERSION}. Дроби, знаки, ведущие нули у
     * ненулевого числа и значения за пределами диапазона отклоняет с {@code VALIDATION_ERROR}.
     *
     * @param value ожидаемая версия корзины из параметра HTTP-запроса
     * @return проверенная версия корзины от 0 до {@code MAX_VERSION}
     */
    public long version(String value) {
        require(value != null && value.matches("(0|[1-9][0-9]{0,15})"), "Invalid expectedCartVersion");
        long number = Long.parseLong(value);
        require(number <= MAX_VERSION, "Invalid expectedCartVersion");
        return number;
    }
    /**
     * Проверяет идентификатор длиной от 1 до 64 символов. Первый символ должен быть латинской буквой или
     * цифрой; далее разрешены также {@code . _ : -}. Возвращает исходную строку, а нарушение формата отклоняет
     * с {@code VALIDATION_ERROR}.
     *
     * @param value проверяемый идентификатор магазина, продукта или строки
     * @return допустимый идентификатор без изменений
     */
    public String identifier(String value) {
        require(value != null && value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,63}"), "Invalid identifier");
        return value;
    }
    /**
     * Преобразует строку в UUID только в полном формате из пяти групп: 8, 4, 4, 4 и 12 шестнадцатеричных цифр.
     * Сокращённую или неверную запись отклоняет с {@code VALIDATION_ERROR}.
     *
     * @param value строковая запись UUID
     * @return UUID, прочитанный из полной строковой записи
     */
    public UUID uuid(String value) {
        require(value != null && value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"), "Invalid UUID");
        return UUID.fromString(value);
    }
    /**
     * Преобразует объект в JSON для сохранения или отправки. Если преобразование невозможно, выбрасывает
     * {@code IllegalStateException} и прерывает текущую операцию.
     *
     * @param value Java-объект для преобразования в JSON
     * @return JSON-запись переданного объекта
     */
    public String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot serialize stock receipt", failure);
        }
    }
    /**
     * Вычисляет контрольную сумму уже упорядоченного содержимого поставки. Одинаковые цены, даты и строки дают
     * одинаковую сумму для проверки повторного прихода.
     *
     * @param payload проверенное содержимое оприходованной поставки с упорядоченными строками
     * @return контрольная сумма содержимого поставки в шестнадцатеричной записи
     */
    private String fingerprint(PostedPayload payload) {
        return hash(json(payload));
    }
    /**
     * Вычисляет SHA-256 строки в кодировке UTF-8 и возвращает шестнадцатеричную запись. Если алгоритм
     * недоступен, выбрасывает {@code IllegalStateException}.
     *
     * @param value текст, для которого вычисляется контрольная сумма
     * @return контрольная сумма SHA-256 в шестнадцатеричной записи
     */
    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }
    /**
     * Создаёт ошибку HTTP 400 с кодом {@code VALIDATION_ERROR} и переданным пояснением. Сам метод ошибку не
     * выбрасывает.
     *
     * @param message пояснение для клиента без секретов и внутренних подробностей
     * @return исключение магазина с подготовленными статусом, кодом и пояснением
     */
    private ShopException invalid(String message) {
        return new ShopException(400, "VALIDATION_ERROR", message);
    }
    /**
     * Продолжает проверку, если условие выполнено. Иначе выбрасывает ошибку HTTP 400 с кодом {@code
     * VALIDATION_ERROR} и переданным пояснением.
     *
     * @param condition условие, которое должно выполняться для допустимых данных
     * @param message пояснение для клиента без секретов и внутренних подробностей
     */
    private void require(boolean condition, String message) {
        if (!condition) throw invalid(message);
    }
}
