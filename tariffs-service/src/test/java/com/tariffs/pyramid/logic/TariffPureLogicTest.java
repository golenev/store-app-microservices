package com.tariffs.pyramid.logic;

import com.tariffs.repository.TariffRuleRepository;
import org.junit.jupiter.api.*;
import org.springframework.util.ReflectionUtils;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.Objects;
import static org.assertj.core.api.Assertions.*;

/** Форматирование ставки TARIFFS без БД: проверяемый метод работает только с переданным BigDecimal. */
@Tag("logic")
class TariffPureLogicTest {
    private final TariffRuleRepository repository = new TariffRuleRepository(null);

    /**
     * TAR-LOGIC-001. Ставка 0.200000 приходит с точностью SQL-колонки.
     * Проверяем строку 0.20: лишние нули убраны, два десятичных знака сохранены.
     */
    @Test @DisplayName("TAR-LOGIC-001: лишние нули ставки удаляются")
    void removesTrailingZeros() { assertThat(format("0.200000")).isEqualTo("0.20"); }

    /**
     * TAR-LOGIC-002. Ставка 0.123456 содержит шесть значимых десятичных цифр.
     * Проверяем их сохранение без округления: форматирование не должно менять размер наценки.
     */
    @Test @DisplayName("TAR-LOGIC-002: значимая точность ставки сохраняется")
    void preservesFractionalPrecision() { assertThat(format("0.123456")).isEqualTo("0.123456"); }

    /**
     * TAR-LOGIC-003. Нулевая ставка разрешена и должна передаваться как 0.00.
     * Проверяем два знака после точки, чтобы формат нуля совпадал с форматом остальных ставок.
     */
    @Test @DisplayName("TAR-LOGIC-003: нулевая ставка имеет два знака")
    void formatsZero() { assertThat(format("0.000000")).isEqualTo("0.00"); }

    /**
     * Вызывает непубличный formatRate через ReflectionUtils.
     * JdbcTemplate намеренно отсутствует: выбранная функция не читает поля репозитория и не выполняет SQL.
     * @param value точное десятичное значение ставки
     * @return строка без лишних нулей, минимум с двумя десятичными знаками
     */
    private String format(String value) {
        Method method = Objects.requireNonNull(ReflectionUtils.findMethod(TariffRuleRepository.class, "formatRate", BigDecimal.class));
        ReflectionUtils.makeAccessible(method);
        return (String) ReflectionUtils.invokeMethod(method, repository, new BigDecimal(value));
    }
}
