package unit;

import com.shop.store.entity.Product;
import com.shop.store.model.TariffDto;
import com.shop.store.service.KafkaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Для проверки приватного {@code applyTariff} не нужны моки: метод полностью
 * основан на переданных моделях {@link Product} и списке {@link TariffDto},
 * поэтому используем реальные экземпляры и через ReflectionTestUtils вызываем
 * чистую бизнес-логику без подмены зависимостей. Mockito и WireMock не дают
 * дополнительных преимуществ, потому что у метода нет внешних коллабораций,
 * и их применение добавило бы лишний уровень абстракции.
 */
class ApplyTariffTest {

    private KafkaService kafkaService;

    /**
     * Creates a fresh service before each scenario to isolate mutable test state.
     */
    @BeforeEach
    void setUp() {
        kafkaService = new KafkaService();
    }

    /**
     * Given price 200 and a matching 15 percent tariff, verifies the adjusted price is 230.
     */
    @Test
    @DisplayName("корректно повышает цену при наличии подходящего тарифа")
    void increasesPriceWhenTariffFound() {
        Product product = new Product();
        product.setFoodstuff(false);
        product.setPrice(BigDecimal.valueOf(200));

        TariffDto matchingTariff = new TariffDto();
        matchingTariff.setProductType("not_food_500");
        matchingTariff.setMarkupCoefficient(BigDecimal.valueOf(15));

        TariffDto anotherTariff = new TariffDto();
        anotherTariff.setProductType("food_100");
        anotherTariff.setMarkupCoefficient(BigDecimal.valueOf(5));

        List<TariffDto> tariffs = List.of(anotherTariff, matchingTariff);

        ReflectionTestUtils.invokeMethod(kafkaService, "applyTariff", product, tariffs);

        assertThat(product.getPrice()).isEqualByComparingTo("230");
    }

    /**
     * Given a product with no matching tariff, verifies its original price remains unchanged.
     */
    @Test
    @DisplayName("оставляет цену без изменений, если тариф не найден")
    void leavesPriceWhenTariffMissing() {
        Product product = new Product();
        product.setFoodstuff(true);
        product.setPrice(BigDecimal.valueOf(80));

        TariffDto tariff = new TariffDto();
        tariff.setProductType("not_food_100");
        tariff.setMarkupCoefficient(BigDecimal.TEN);

        List<TariffDto> tariffs = List.of(tariff);

        ReflectionTestUtils.invokeMethod(kafkaService, "applyTariff", product, tariffs);

        assertThat(product.getPrice()).isEqualByComparingTo("80");
    }
}
