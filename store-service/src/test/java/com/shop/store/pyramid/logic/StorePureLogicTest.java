package com.shop.store.pyramid.logic;

import com.shop.store.codec.ShopCodec;
import com.shop.store.exception.ShopException;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.util.ReflectionUtils;
import java.lang.reflect.Method;
import java.util.Objects;
import static org.assertj.core.api.Assertions.*;

/**
 * Проверяет формат и допустимые значения цены магазина прямым вызовом кода. Spring, БД и Kafka не
 * запускаются.
 */
@Tag("logic")
class StorePureLogicTest {
    private final ShopCodec codec = new ShopCodec(new ObjectMapper());

    /**
     * STORE-LOGIC-001. Передаёт проверке цены строку {@code 100.05}. Ожидает ту же строку со всеми цифрами и
     * двумя знаками после точки; округление не требуется. Вызывает непубличный метод через {@code
     * ReflectionUtils} без запуска Spring.
     */
    @Test @DisplayName("STORE-LOGIC-001: корректная денежная строка сохраняется")
    void preservesExactPrice() { assertThat(price("100.05")).isEqualTo("100.05"); }

    /**
     * STORE-LOGIC-002. Передаёт {@code 0.00}: формат денежной строки верен, но цена должна быть положительной.
     * Проверяет {@code VALIDATION_ERROR}, чтобы ноль не был принят.
     */
    @Test @DisplayName("STORE-LOGIC-002: нулевая цена отклоняется")
    void rejectsZeroPrice() {
        assertThatThrownBy(() -> price("0.00")).isInstanceOfSatisfying(ShopException.class,
                error -> assertThat(error.code()).isEqualTo("VALIDATION_ERROR"));
    }

    /**
     * STORE-LOGIC-003. Передаёт {@code 100.005}, содержащую третью цифру после точки. Проверяет {@code
     * VALIDATION_ERROR}; функция не должна округлять неверный ввод до допустимой цены.
     */
    @Test @DisplayName("STORE-LOGIC-003: лишняя точность отклоняется")
    void rejectsExcessPrecision() {
        assertThatThrownBy(() -> price("100.005")).isInstanceOfSatisfying(ShopException.class,
                error -> assertThat(error.code()).isEqualTo("VALIDATION_ERROR"));
    }

    /**
     * Находит и вызывает непубличную проверку цены с указанной строкой. Объект создан напрямую и не обращается
     * к БД; отсутствие метода или ошибка проверки завершает вызов исключением.
     *
     * @param value проверяемая строковая цена
     * @return проверенная положительная цена без округления
     */
    private String price(String value) {
        Method method = Objects.requireNonNull(ReflectionUtils.findMethod(ShopCodec.class, "price", String.class));
        ReflectionUtils.makeAccessible(method);
        return (String) ReflectionUtils.invokeMethod(method, codec, value);
    }
}
