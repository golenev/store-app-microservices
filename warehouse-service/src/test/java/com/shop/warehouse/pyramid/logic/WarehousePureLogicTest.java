package com.shop.warehouse.pyramid.logic;

import com.shop.warehouse.codec.DeliveryCodec;
import com.shop.warehouse.exception.DeliveryException;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.util.ReflectionUtils;
import java.lang.reflect.Method;
import java.util.Objects;
import static org.assertj.core.api.Assertions.*;

/**
 * Проверяет допустимые символы и длину идентификатора поставки прямым вызовом кода. HTTP, Spring, БД и
 * Kafka не запускаются.
 */
@Tag("logic")
class WarehousePureLogicTest {
    private final DeliveryCodec codec = new DeliveryCodec(new ObjectMapper());

    /**
     * WH-LOGIC-001. Передаёт допустимый идентификатор {@code D-1} через {@code ReflectionUtils}. Ожидает
     * исходную строку без изменений.
     */
    @Test @DisplayName("WH-LOGIC-001: допустимый идентификатор сохраняется")
    void acceptsIdentifier() { assertThat(identifier("D-1")).isEqualTo("D-1"); }

    /**
     * WH-LOGIC-002. Передаёт идентификатор {@code D 1} с пробелом. Проверяет {@code VALIDATION_ERROR}:
     * неверный ввод не исправляется и не принимается.
     */
    @Test @DisplayName("WH-LOGIC-002: пробел в идентификаторе отклоняется")
    void rejectsWhitespace() {
        assertThatThrownBy(() -> identifier("D 1")).isInstanceOfSatisfying(DeliveryException.class,
                error -> assertThat(error.code()).isEqualTo("VALIDATION_ERROR"));
    }

    /**
     * WH-LOGIC-003. Проверяет границу длины идентификатора: 64 допустимых символа принимаются, 65 вызывают
     * ошибку. Внешние системы не используются.
     */
    @Test @DisplayName("WH-LOGIC-003: граница длины 64 символа")
    void checksLengthBoundary() {
        assertThat(identifier("D".repeat(64))).hasSize(64);
        assertThatThrownBy(() -> identifier("D".repeat(65))).isInstanceOf(DeliveryException.class);
    }

    /**
     * Вызывает проверку идентификатора через {@code ReflectionUtils} без запуска приложения. Метод публичный и
     * может вызываться напрямую; здесь отражение используется для учебного сравнения способов вызова.
     *
     * @param value проверяемый идентификатор поставки
     * @return допустимый идентификатор без изменений
     */
    private String identifier(String value) {
        Method method = Objects.requireNonNull(ReflectionUtils.findMethod(DeliveryCodec.class, "identifier", String.class));
        return (String) ReflectionUtils.invokeMethod(method, codec, value);
    }
}
