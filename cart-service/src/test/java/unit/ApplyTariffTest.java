package unit;

import com.experience_kafka.entity.Product;
import com.experience_kafka.model.TariffDto;
import com.experience_kafka.service.KafkaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ApplyTariffTest {

    private KafkaService kafkaService;

    @BeforeEach
    void setUp() {
        kafkaService = new KafkaService();
    }

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
