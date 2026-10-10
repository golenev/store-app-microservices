package com.shop.store.codec;

import com.shop.store.exception.ShopException;
import com.shop.store.messaging.dto.GoodsEvent;
import com.shop.store.messaging.dto.PostedLine;
import jakarta.validation.Validator;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashSet;
import java.util.Set;

/** Проверяет ограничения DTO и связанные условия поставки без чтения JSON и обращения к БД. */
@Component
public class ShopInputValidator {
    private final Validator validator;

    /**
     * Подключает проверку аннотаций DTO; жизненным циклом Validator управляет Spring.
     * @param validator проверка ограничений полей и вложенных DTO
     */
    public ShopInputValidator(Validator validator) { this.validator = validator; }

    /**
     * Проверяет ограничения заполненного DTO, включая вложенные позиции; данные не меняет.
     * @param input прочитанный DTO
     * @throws ShopException при нарушении ограничения с кодом VALIDATION_ERROR
     */
    public void validate(Object input) {
        require(input != null, "Missing input");
        require(validator.validate(input).isEmpty(), "Invalid input fields");
    }

    /**
     * Проверяет поля события, даты, уникальность строк и расчёт цены. БД и транзакции не использует.
     * @param event прочитанное событие GoodsPosted
     * @throws ShopException при нарушении контракта с кодом VALIDATION_ERROR
     */
    public void goods(GoodsEvent event) {
        validate(event);
        var payload = event.payload();
        require(!payload.postedAt().isBefore(payload.receivedAt())
                && payload.postedAt().equals(event.occurredAt()), "Inconsistent GoodsPosted timestamps");
        Set<String> lineIds = new HashSet<>();
        Set<String> products = new HashSet<>();
        for (PostedLine line : payload.items()) {
            require(lineIds.add(line.lineId()) && products.add(line.productId()), "Duplicate lineId or productId");
            require(!line.shortName().isBlank()
                    && line.shortName().codePointCount(0, line.shortName().length()) <= 255, "Invalid shortName");
            require(line.description().codePointCount(0, line.description().length()) <= 2000, "Invalid description");
            price(line.purchasePrice());
            price(line.salePrice());
            BigDecimal markup = new BigDecimal(line.markupRate());
            String calculated = new BigDecimal(line.purchasePrice()).multiply(BigDecimal.ONE.add(markup))
                    .setScale(2, RoundingMode.HALF_UP).toPlainString();
            require(calculated.equals(line.salePrice()), "salePrice does not match purchasePrice and markupRate");
        }
    }

    /**
     * Проверяет положительную цену с двумя знаками после точки и не более чем 26 цифрами до неё.
     * @param value строковая цена
     * @return исходная цена без округления
     */
    private String price(String value) {
        require(value != null && value.matches("(0|[1-9][0-9]{0,25})\\.[0-9]{2}")
                && new BigDecimal(value).signum() > 0, "Invalid price");
        return value;
    }

    /**
     * Отклоняет неверное условие как ошибку входного контракта.
     * @param condition проверяемое условие
     * @param message безопасное описание ошибки
     */
    private void require(boolean condition, String message) {
        if (!condition) throw new ShopException(400, "VALIDATION_ERROR", message);
    }
}
