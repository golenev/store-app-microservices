package com.shop.warehouse.pyramid;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.warehouse.delivery.*;
import org.junit.jupiter.api.*;
import org.springframework.util.ReflectionUtils;
import java.lang.reflect.Method;
import java.util.Objects;
import static org.assertj.core.api.Assertions.*;

/** Чистая валидация идентификаторов WAREHOUSE без HTTP, Spring, БД и Kafka. */
@Tag("logic")
class WarehousePureLogicTest {
    private final DeliveryCodec codec = new DeliveryCodec(new ObjectMapper());

    /**
     * WH-LOGIC-001. Идентификатор D-1 содержит разрешённые символы.
     * Вызываем production-функцию через ReflectionUtils и проверяем возврат исходной строки без изменений.
     */
    @Test @DisplayName("WH-LOGIC-001: допустимый идентификатор сохраняется")
    void acceptsIdentifier() { assertThat(identifier("D-1")).isEqualTo("D-1"); }

    /**
     * WH-LOGIC-002. Пробел внутри D 1 нарушает формат идентификатора.
     * Проверяем VALIDATION_ERROR: функция не должна исправлять ввод или принимать значение с пробелом.
     */
    @Test @DisplayName("WH-LOGIC-002: пробел в идентификаторе отклоняется")
    void rejectsWhitespace() {
        assertThatThrownBy(() -> identifier("D 1")).isInstanceOfSatisfying(DeliveryException.class,
                error -> assertThat(error.code()).isEqualTo("VALIDATION_ERROR"));
    }

    /**
     * WH-LOGIC-003. Предел длины идентификатора равен 64 символам.
     * Проверяем обе стороны границы: 64 разрешены, 65 возвращают ошибку. Внешние зависимости отсутствуют.
     */
    @Test @DisplayName("WH-LOGIC-003: граница длины 64 символа")
    void checksLengthBoundary() {
        assertThat(identifier("D".repeat(64))).hasSize(64);
        assertThatThrownBy(() -> identifier("D".repeat(65))).isInstanceOf(DeliveryException.class);
    }

    /**
     * Находит и вызывает существующую функцию проверки идентификатора; создание приложения не требуется.
     * Метод публичный и доступен напрямую, ReflectionUtils здесь используется для учебного сравнения способов вызова.
     * @param value исходная строка
     * @return допустимый идентификатор; недопустимый ввод вызывает DeliveryException
     */
    private String identifier(String value) {
        Method method = Objects.requireNonNull(ReflectionUtils.findMethod(DeliveryCodec.class, "identifier", String.class));
        return (String) ReflectionUtils.invokeMethod(method, codec, value);
    }
}
