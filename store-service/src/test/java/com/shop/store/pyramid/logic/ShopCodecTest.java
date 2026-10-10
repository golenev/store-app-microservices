package com.shop.store.pyramid.logic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.shop.store.codec.ShopCodec;
import com.shop.store.codec.ShopInputValidator;
import com.shop.store.exception.ShopException;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.Instant;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/** Проверяет строгий входной контракт STORE и нормализацию без Spring, БД и Kafka. */
@Tag("logic")
class ShopCodecTest {
    private final ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final ShopCodec codec = new ShopCodec(mapper, new ShopInputValidator(factory.getValidator()));
    private static final String LINE = """
            {"lineId":"L-1","productId":"P-1","productType":"FOOD","shortName":"Товар",
             "description":"","quantity":2,"purchasePrice":"100.05","currency":"RUB",
             "markupRate":"0.2","tariffRuleId":"12345678-1234-1234-1234-123456789abc",
             "tariffVersion":1,"salePrice":"120.06"}
            """;
    private static final String GOODS = """
            {"eventId":"abcdefab-1234-1234-1234-123456789abc","eventType":"GoodsPosted","schemaVersion":1,
             "occurredAt":"2026-10-10T12:00:00Z","storeId":"S-1",
             "payload":{"deliveryId":"D-1","deliverySequence":1,"receivedAt":"2026-10-10T11:00:00Z",
             "postedAt":"2026-10-10T12:00:00Z","items":[%s]}}
            """.formatted(LINE);

    /** Освобождает ресурсы валидатора после каждого независимого сценария. */
    @AfterEach
    void closeValidator() { factory.close(); }

    /** Передаёт крайние допустимые числа; ожидает точные значения без потери разрядов. */
    @Test
    void acceptsIntegerBoundaries() {
        var put = codec.put("{\"quantity\":2147483647,\"expectedCartVersion\":9007199254740991}");
        assertThat(put.quantity()).isEqualTo(Integer.MAX_VALUE);
        assertThat(put.expectedCartVersion()).isEqualTo(ShopCodec.MAX_VERSION);
        assertThat(codec.submit("{\"expectedCartVersion\":0}").expectedCartVersion()).isZero();
    }

    /**
     * Передаёт неверные типы, границы и структуру изменения корзины; каждый ввод получает HTTP 400.
     * @param raw самостоятельный некорректный JSON-запрос
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "{}", "null", "[]", "", "{",
            "{\"quantity\":1}", "{\"expectedCartVersion\":0}",
            "{\"quantity\":null,\"expectedCartVersion\":0}",
            "{\"quantity\":1,\"expectedCartVersion\":null}",
            "{\"quantity\":\"2\",\"expectedCartVersion\":0}",
            "{\"quantity\":2.0,\"expectedCartVersion\":0}",
            "{\"quantity\":true,\"expectedCartVersion\":0}",
            "{\"quantity\":[],\"expectedCartVersion\":0}",
            "{\"quantity\":0,\"expectedCartVersion\":0}",
            "{\"quantity\":2147483648,\"expectedCartVersion\":0}",
            "{\"quantity\":1,\"expectedCartVersion\":-1}",
            "{\"quantity\":1,\"expectedCartVersion\":9007199254740992}",
            "{\"quantity\":1,\"expectedCartVersion\":9223372036854775808}",
            "{\"quantity\":1,\"expectedCartVersion\":\"0\"}",
            "{\"quantity\":1,\"expectedCartVersion\":0.0}",
            "{\"quantity\":1,\"expectedCartVersion\":0,\"price\":1}",
            "{\"quantity\":1,\"quantity\":2,\"expectedCartVersion\":0}",
            "{\"quantity\":1,\"expectedCartVersion\":0} {}"
    })
    void rejectsInvalidPut(String raw) { assertInvalid(() -> codec.put(raw)); }

    /**
     * Передаёт неверную версию или лишние поля оформления; запрос отклоняется до бизнес-операции.
     * @param raw самостоятельный некорректный JSON-запрос
     */
    @ParameterizedTest
    @ValueSource(strings = {"{}", "null", "[]", "{\"expectedCartVersion\":null}",
            "{\"expectedCartVersion\":\"0\"}", "{\"expectedCartVersion\":0.0}",
            "{\"expectedCartVersion\":false}", "{\"expectedCartVersion\":-1}",
            "{\"expectedCartVersion\":9007199254740992}",
            "{\"expectedCartVersion\":0,\"items\":[]}",
            "{\"expectedCartVersion\":0,\"expectedCartVersion\":1}",
            "{\"expectedCartVersion\":0} null"})
    void rejectsInvalidSubmit(String raw) { assertInvalid(() -> codec.submit(raw)); }

    /** Читает допустимую поставку; сохраняет даты, UUID и точную цену, нормализует наценку. */
    @Test
    void readsTypedGoods() {
        var result = codec.goods(GOODS);
        assertThat(result.event().eventId()).isEqualTo(UUID.fromString("abcdefab-1234-1234-1234-123456789abc"));
        assertThat(result.event().occurredAt()).isEqualTo(Instant.parse("2026-10-10T12:00:00Z"));
        assertThat(result.event().payload().items().getFirst().markupRate()).isEqualTo("0.200000");
        assertThat(result.event().payload().items().getFirst().salePrice()).isEqualTo("120.06");
    }

    /** Для фиксированной поставки сравнивает сумму с результатом ShopCodec из базовой master. */
    @Test
    void preservesFingerprintFromPreviousCodec() {
        assertThat(codec.goods(GOODS).fingerprint())
                .isEqualTo("29e3ef680127c6d851fa8b07eba7f82bd417190895b5833a508510357366dacd");
    }

    /** Строгий reader отклоняет строковое число; общий mapper приложения сохраняет прежние настройки. */
    @Test
    void doesNotModifyApplicationMapper() throws Exception {
        String raw = "{\"quantity\":\"2\",\"expectedCartVersion\":0}";
        assertInvalid(() -> codec.put(raw));
        assertThat(mapper.readValue(raw, com.shop.store.dto.PutItem.class).quantity()).isEqualTo(2);
    }

    /** Сохранённая пустая корзина с отсутствующим submissionId читается прежним mapper без входных ограничений. */
    @Test
    void preservesCartSnapshotReading() {
        var cart = new com.shop.store.dto.Cart("S-1", UUID.randomUUID(), 0, "OPEN",
                java.util.List.of(), "0.00", "RUB", null);
        assertThat(codec.cartSnapshot(codec.json(cart))).isEqualTo(cart);
    }

    /** Принимает 1000 разных строк и отклоняет 1001; размер проверяется до обработки цены и повторов. */
    @Test
    void enforcesDeliverySizeLimit() {
        String thousand = java.util.stream.IntStream.range(0, 1000)
                .mapToObj(index -> LINE.replace("L-1", "L-" + index).replace("P-1", "P-" + index))
                .collect(java.util.stream.Collectors.joining(","));
        assertThat(codec.goods(GOODS.replace(LINE, thousand)).event().payload().items()).hasSize(1000);
        assertInvalid(() -> codec.goods(GOODS.replace(LINE, thousand + ","
                + LINE.replace("L-1", "L-1000").replace("P-1", "P-1000"))));
    }

    /** Переставляет две разные строки и меняет запись наценки; нормализованные события и суммы совпадают. */
    @Test
    void fingerprintIgnoresLineOrderAndMarkupScale() {
        String second = LINE.replace("L-1", "L-2").replace("P-1", "P-2");
        String firstOrder = GOODS.replace(LINE, LINE + "," + second);
        String reverseOrder = GOODS.replace(LINE, second + "," + LINE).replace("\"0.2\"", "\"0.200000\"");
        var first = codec.goods(firstOrder);
        var reversed = codec.goods(reverseOrder);
        assertThat(first.event()).isEqualTo(reversed.event());
        assertThat(first.fingerprint()).isEqualTo(reversed.fingerprint());
        assertThat(first.event().payload().items()).extracting(line -> line.lineId()).containsExactly("L-1", "L-2");
    }

    /** Меняет содержимое поставки при прежнем deliveryId; контрольная сумма должна измениться. */
    @Test
    void fingerprintDetectsChangedDeliveryContent() {
        assertThat(codec.goods(GOODS).fingerprint())
                .isNotEqualTo(codec.goods(GOODS.replace("\"quantity\":2", "\"quantity\":3")).fingerprint());
    }

    /**
     * Подставляет неверное скалярное значение в строку поставки; сохраняется строгость JSON-типов.
     * @param quantity JSON-значение количества
     */
    @ParameterizedTest
    @ValueSource(strings = {"\"2\"", "2.0", "null", "true", "0", "-1", "2147483648"})
    void rejectsInvalidGoodsQuantity(String quantity) {
        assertInvalid(() -> codec.goods(GOODS.replace("\"quantity\":2", "\"quantity\":" + quantity)));
    }

    /** Передаёт пропущенное, null, числовое и лишнее строковое поле во вложенной позиции; все отклоняются. */
    @Test
    void rejectsMalformedNestedFields() {
        assertInvalid(() -> codec.goods(GOODS.replace("\"description\":\"\",", "")));
        assertInvalid(() -> codec.goods(GOODS.replace("\"description\":\"\"", "\"description\":null")));
        assertInvalid(() -> codec.goods(GOODS.replace("\"description\":\"\"", "\"description\":123")));
        assertInvalid(() -> codec.goods(GOODS.replace("\"description\":\"\"", "\"description\":true")));
        assertInvalid(() -> codec.goods(GOODS.replace("\"description\":\"\"", "\"description\":\"\",\"extra\":1")));
        assertInvalid(() -> codec.goods(GOODS.replace("\"quantity\":2", "\"quantity\":2,\"quantity\":3")));
        assertInvalid(() -> codec.goods(GOODS.replace("\"items\":[", "\"items\":[null,")));
    }

    /** Передаёт неизвестный тип/версию, пустые позиции и лишние поля конверта/содержимого; они отклоняются. */
    @Test
    void rejectsMalformedEnvelopeAndPayload() {
        assertInvalid(() -> codec.goods(GOODS.replace("GoodsPosted", "OrderSubmitted")));
        assertInvalid(() -> codec.goods(GOODS.replace("\"schemaVersion\":1", "\"schemaVersion\":2")));
        assertInvalid(() -> codec.goods(GOODS.replace("\"schemaVersion\":1,", "")));
        assertInvalid(() -> codec.goods(GOODS.replace("\"schemaVersion\":1", "\"schemaVersion\":1.0")));
        assertInvalid(() -> codec.goods(GOODS.replace("\"storeId\":\"S-1\"", "\"storeId\":1")));
        assertInvalid(() -> codec.goods(GOODS.replace("\"storeId\":\"S-1\"", "\"storeId\":\"S-1\",\"extra\":1")));
        assertInvalid(() -> codec.goods(GOODS.replace("\"deliverySequence\":1", "\"deliverySequence\":null")));
        assertInvalid(() -> codec.goods(GOODS.replace("\"deliverySequence\":1", "\"deliverySequence\":9007199254740992")));
        assertInvalid(() -> codec.goods(GOODS.replace("\"deliverySequence\":1", "\"deliverySequence\":1,\"extra\":1")));
        assertInvalid(() -> codec.goods(GOODS.replace(LINE, "")));
        assertInvalid(() -> codec.goods(GOODS + " {}"));
    }

    /** Сокращённый, числовой и base64 UUID отклоняются; полный UUID в верхнем регистре принимается. */
    @Test
    void preservesStrictUuidFormat() {
        assertInvalid(() -> codec.goods(GOODS.replace("abcdefab-1234-1234-1234-123456789abc", "1-1-1-1-1")));
        assertInvalid(() -> codec.goods(GOODS.replace("abcdefab-1234-1234-1234-123456789abc", "AAAAAAAAAAAAAAAAAAAAAA==")));
        assertInvalid(() -> codec.goods(GOODS.replace("\"abcdefab-1234-1234-1234-123456789abc\"", "123")));
        assertInvalid(() -> codec.goods(GOODS.replace("12345678-1234-1234-1234-123456789abc", "1-1-1-1-1")));
        assertThat(codec.goods(GOODS.replace("abcdefab", "ABCDEFAB")).event().eventId())
                .isEqualTo(codec.goods(GOODS).event().eventId());
    }

    /** Даты с другим смещением, числовые и повреждённые даты отклоняются; нарушенный порядок тоже. */
    @Test
    void preservesUtcAndTimestampRelations() {
        assertInvalid(() -> codec.goods(GOODS.replace("2026-10-10T12:00:00Z", "2026-10-10T12:00:00+00:00")));
        assertInvalid(() -> codec.goods(GOODS.replace("\"2026-10-10T12:00:00Z\"", "123")));
        assertInvalid(() -> codec.goods(GOODS.replace("2026-10-10T12:00:00Z", "invalidZ")));
        assertInvalid(() -> codec.goods(GOODS.replace("2026-10-10T11:00:00Z", "2026-10-10T13:00:00Z")));
        assertInvalid(() -> codec.goods(GOODS.replace("\"postedAt\":\"2026-10-10T12:00:00Z\"",
                "\"postedAt\":\"2026-10-10T12:01:00Z\"")));
    }

    /** Две строки с одинаковым lineId или productId отклоняются независимо от других полей. */
    @Test
    void rejectsDuplicateLinesAndProducts() {
        assertInvalid(() -> codec.goods(GOODS.replace(LINE, LINE + "," + LINE.replace("P-1", "P-2"))));
        assertInvalid(() -> codec.goods(GOODS.replace(LINE, LINE + "," + LINE.replace("L-1", "L-2"))));
    }

    /** Нулевая, дробная и числовая цена, неверная наценка и неправильный результат расчёта отклоняются. */
    @Test
    void rejectsInvalidMoneyAndMarkup() {
        assertInvalid(() -> codec.goods(GOODS.replace("100.05", "0.00")));
        assertInvalid(() -> codec.goods(GOODS.replace("100.05", "100.005")));
        assertInvalid(() -> codec.goods(GOODS.replace("\"100.05\"", "100.05")));
        assertInvalid(() -> codec.goods(GOODS.replace("120.06", "120.07")));
        assertInvalid(() -> codec.goods(GOODS.replace("\"0.2\"", "\"0.2000001\"")));
        assertInvalid(() -> codec.goods(GOODS.replace("\"0.2\"", "0.2")));
    }

    /** Проверяет ограничения имён в кодовых точках: 255 emoji допустимы, 256 превышают предел. */
    @Test
    void countsUnicodeCodePoints() {
        assertThat(codec.goods(GOODS.replace("Товар", "😀".repeat(255))).event().payload().items()).hasSize(1);
        assertInvalid(() -> codec.goods(GOODS.replace("Товар", "😀".repeat(256))));
        assertInvalid(() -> codec.goods(GOODS.replace("Товар", "   ")));
        assertThat(codec.goods(GOODS.replace("\"description\":\"\"",
                "\"description\":\"" + "😀".repeat(2000) + "\"")).event().payload().items()).hasSize(1);
        assertInvalid(() -> codec.goods(GOODS.replace("\"description\":\"\"",
                "\"description\":\"" + "😀".repeat(2001) + "\"")));
    }

    /** Передаёт неверные идентификаторы, тип товара, валюту и версию тарифа; ограничения DTO отклоняют событие. */
    @Test
    void validatesPostedLineConstraints() {
        assertInvalid(() -> codec.goods(GOODS.replace("L-1", "-invalid")));
        assertInvalid(() -> codec.goods(GOODS.replace("P-1", "x".repeat(65))));
        assertInvalid(() -> codec.goods(GOODS.replace("FOOD", "UNKNOWN")));
        assertInvalid(() -> codec.goods(GOODS.replace("RUB", "USD")));
        assertInvalid(() -> codec.goods(GOODS.replace("\"tariffVersion\":1", "\"tariffVersion\":0")));
        assertInvalid(() -> codec.goods(GOODS.replace("\"tariffVersion\":1", "\"tariffVersion\":9007199254740992")));
    }

    /**
     * Читает неверный запрос; проверяет одновременно HTTP 400 и стабильный код ошибки контракта.
     * @param action вызов чтения самостоятельного некорректного запроса
     */
    private void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ShopException.class, error -> {
            assertThat(error.status()).isEqualTo(400);
            assertThat(error.code()).isEqualTo("VALIDATION_ERROR");
        });
    }
}
