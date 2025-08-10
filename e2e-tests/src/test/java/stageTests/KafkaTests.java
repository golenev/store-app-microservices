package stageTests;

import config.Database;
import models.ProductPayload;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import testUtil.KafkaProducerImpl;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

@Execution(ExecutionMode.SAME_THREAD)
@DisplayName("Проверка получения товара через Kafka")
public class KafkaTests {

    private Long barcodeId;

    @AfterEach
    void cleanup() {
        if (barcodeId != null) {
            JdbcTemplate template = Database.template();
            template.update("DELETE FROM cart WHERE barcode_id = ?", barcodeId);
            template.update("DELETE FROM product WHERE barcode_id = ?", barcodeId);
        }
    }

    private void sendAndAssert(ProductPayload payload, BigDecimal markupCoefficient) {
        barcodeId = UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;
        payload.setBarcodeId(barcodeId);
        KafkaProducerImpl producer = new KafkaProducerImpl();
        producer.sendMessage("send-topic", payload);

        JdbcTemplate template = Database.template();
        BigDecimal expectedPrice = payload.getPrice()
                .add(payload.getPrice().multiply(markupCoefficient).divide(BigDecimal.valueOf(100)));

        Awaitility.await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            BigDecimal price;
            try {
                price = template.queryForObject(
                        "SELECT price FROM product WHERE barcode_id = ?",
                        BigDecimal.class,
                        barcodeId);
            } catch (EmptyResultDataAccessException e) {
                throw new AssertionError("Запись ещё не создана", e);
            }
            Assertions.assertEquals(0, expectedPrice.compareTo(price));
        });
    }

    @Test
    @DisplayName("наценка применяется для food_100")
    void testFood100() {
        sendAndAssert(new ProductPayload(
                null,
                "food100",
                "desc",
                new BigDecimal("100"),
                1,
                LocalDateTime.now().toString(),
                true
        ), BigDecimal.valueOf(1));
    }

    @Test
    @DisplayName("наценка применяется для food_300")
    void testFood300() {
        sendAndAssert(new ProductPayload(
                null,
                "food300",
                "desc",
                new BigDecimal("200"),
                1,
                LocalDateTime.now().toString(),
                true
        ), BigDecimal.valueOf(3));
    }

    @Test
    @DisplayName("наценка применяется для food_500")
    void testFood500() {
        sendAndAssert(new ProductPayload(
                null,
                "food500",
                "desc",
                new BigDecimal("400"),
                1,
                LocalDateTime.now().toString(),
                true
        ), BigDecimal.valueOf(5));
    }

    @Test
    @DisplayName("наценка применяется для food_1000")
    void testFood1000() {
        sendAndAssert(new ProductPayload(
                null,
                "food1000",
                "desc",
                new BigDecimal("600"),
                1,
                LocalDateTime.now().toString(),
                true
        ), BigDecimal.valueOf(10));
    }

    @Test
    @DisplayName("наценка применяется для not_food_100")
    void testNotFood100() {
        sendAndAssert(new ProductPayload(
                null,
                "notfood100",
                "desc",
                new BigDecimal("100"),
                1,
                LocalDateTime.now().toString(),
                false
        ), BigDecimal.valueOf(5));
    }

    @Test
    @DisplayName("наценка применяется для not_food_500")
    void testNotFood500() {
        sendAndAssert(new ProductPayload(
                null,
                "notfood500",
                "desc",
                new BigDecimal("400"),
                1,
                LocalDateTime.now().toString(),
                false
        ), BigDecimal.valueOf(10));
    }

    @Test
    @DisplayName("наценка применяется для not_food_1000")
    void testNotFood1000() {
        sendAndAssert(new ProductPayload(
                null,
                "notfood1000",
                "desc",
                new BigDecimal("600"),
                1,
                LocalDateTime.now().toString(),
                false
        ), BigDecimal.valueOf(20));
    }
}
