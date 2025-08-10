package stageTests;

import config.Database;
import models.ProductPayload;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import testUtil.KafkaProducerImpl;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;

@DisplayName("Проверка получения товара через Kafka")
public class KafkaTests {

    @AfterEach
    void cleanup() {
        JdbcTemplate template = Database.template();
        template.update("DELETE FROM cart WHERE barcode_id = ?", 212L);
        template.update("DELETE FROM product WHERE barcode_id = ?", 212L);
    }

    @Test
    @DisplayName("товар из Kafka попадает в БД")
    void test() {
        KafkaProducerImpl producer = new KafkaProducerImpl();
        // отправляем сообщение в Kafka
        producer.sendMessage(
                "send-topic",
                new ProductPayload(
                        212L,
                        "Водочка",
                        "Водка 0.5 л",
                        new BigDecimal("188"),
                        10,
                        LocalDateTime.now().toString(),
                        false));

        // ждём появления товара в базе данных
        JdbcTemplate template = Database.template();
        Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            Integer qty;
            try {
                qty = template.queryForObject(
                        "SELECT quantity FROM product WHERE barcode_id = ?",
                        Integer.class,
                        212L);
            } catch (EmptyResultDataAccessException e) {
                throw new AssertionError("Запись ещё не создана", e);
            }
            Assertions.assertEquals(10, qty);
        });

    }
}