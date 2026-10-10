package com.tariffs.pyramid.logic;

import com.tariffs.repository.TariffRuleRepository;
import org.junit.jupiter.api.*;
import org.springframework.util.ReflectionUtils;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.Objects;
import static org.assertj.core.api.Assertions.*;

/**
 * Проверяет строковую запись наценки прямым вызовом кода репозитория. Метод работает с переданным
 * десятичным числом и не обращается к БД.
 */
@Tag("logic")
class TariffPureLogicTest {
    private final TariffRuleRepository repository = new TariffRuleRepository(null);

    /**
     * TAR-LOGIC-001. Передаёт наценку {@code 0.200000}, как она хранится в SQL-колонке. Ожидает {@code 0.20}:
     * лишние нули удалены, минимум два знака после точки сохранён.
     */
    @Test @DisplayName("TAR-LOGIC-001: лишние нули ставки удаляются")
    void removesTrailingZeros() { assertThat(format("0.200000")).isEqualTo("0.20"); }

    /**
     * TAR-LOGIC-002. Передаёт наценку {@code 0.123456}. Ожидает ту же строку со всеми шестью значимыми цифрами
     * без округления.
     */
    @Test @DisplayName("TAR-LOGIC-002: значимая точность ставки сохраняется")
    void preservesFractionalPrecision() { assertThat(format("0.123456")).isEqualTo("0.123456"); }

    /**
     * TAR-LOGIC-003. Передаёт нулевую наценку {@code 0.000000}. Ожидает строку {@code 0.00}, сохраняющую
     * минимум два десятичных знака.
     */
    @Test @DisplayName("TAR-LOGIC-003: нулевая ставка имеет два знака")
    void formatsZero() { assertThat(format("0.000000")).isEqualTo("0.00"); }

    /**
     * Находит и вызывает непубличный метод {@code formatRate} через {@code ReflectionUtils}. Подключение к БД
     * не передаётся: выбранный метод использует только аргумент и не выполняет SQL.
     *
     * @param value строковая наценка для проверки форматирования
     * @return наценка строкой без лишних нулей, минимум с двумя знаками после точки
     */
    private String format(String value) {
        Method method = Objects.requireNonNull(ReflectionUtils.findMethod(TariffRuleRepository.class, "formatRate", BigDecimal.class));
        ReflectionUtils.makeAccessible(method);
        return (String) ReflectionUtils.invokeMethod(method, repository, new BigDecimal(value));
    }
}
