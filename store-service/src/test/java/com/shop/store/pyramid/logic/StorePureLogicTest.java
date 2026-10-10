package com.shop.store.pyramid.logic;

import com.shop.store.codec.ShopCodec;
import com.shop.store.exception.ShopException;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.util.ReflectionUtils;
import java.lang.reflect.Method;
import java.util.Objects;
import static org.assertj.core.api.Assertions.*;

/** Изолированные проверки значений STORE: Spring, SQL и Kafka не запускаются. ID не связаны с CRUD-сценариями. */
@Tag("logic")
class StorePureLogicTest {
    private final ShopCodec codec = new ShopCodec(new ObjectMapper());

    /**
     * STORE-LOGIC-001. Передаём непубличной функции цену 100.05.
     * Проверяем сохранение всех цифр и двух знаков после точки: функция не должна округлять или менять строку.
     * ReflectionUtils открывает метод price; проверка выполняет production-код без контейнера Spring.
     */
    @Test @DisplayName("STORE-LOGIC-001: корректная денежная строка сохраняется")
    void preservesExactPrice() { assertThat(price("100.05")).isEqualTo("100.05"); }

    /**
     * STORE-LOGIC-002. Нулевая цена недопустима, хотя её строка соответствует денежному формату.
     * Вызываем price со значением 0.00 и проверяем ошибку VALIDATION_ERROR вместо принятия нуля.
     * Проверка различает корректность синтаксиса и требование положительной цены.
     */
    @Test @DisplayName("STORE-LOGIC-002: нулевая цена отклоняется")
    void rejectsZeroPrice() {
        assertThatThrownBy(() -> price("0.00")).isInstanceOfSatisfying(ShopException.class,
                error -> assertThat(error.code()).isEqualTo("VALIDATION_ERROR"));
    }

    /**
     * STORE-LOGIC-003. Передаём 100.005: третью цифру после точки нельзя молча округлить.
     * Проверяем ошибку VALIDATION_ERROR, чтобы неверная цена не стала другой допустимой ценой.
     */
    @Test @DisplayName("STORE-LOGIC-003: лишняя точность отклоняется")
    void rejectsExcessPrecision() {
        assertThatThrownBy(() -> price("100.005")).isInstanceOfSatisfying(ShopException.class,
                error -> assertThat(error.code()).isEqualTo("VALIDATION_ERROR"));
    }

    /**
     * Вызывает непубличную чистую проверку денежной строки с переданным значением.
     * Объект создан напрямую; метод не обращается к хранилищам. Отсутствие метода немедленно завершает тест ошибкой.
     * @param value проверяемая цена
     * @return исходная допустимая строка; ошибка production-валидации распространяется вызывающему тесту
     */
    private String price(String value) {
        Method method = Objects.requireNonNull(ReflectionUtils.findMethod(ShopCodec.class, "price", String.class));
        ReflectionUtils.makeAccessible(method);
        return (String) ReflectionUtils.invokeMethod(method, codec, value);
    }
}
