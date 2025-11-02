package unit;

import com.experience_kafka.entity.Product;
import com.experience_kafka.service.KafkaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class DetermineProductTypeTest {

    private KafkaService kafkaService;

    @BeforeEach
    void setUp() {
        kafkaService = new KafkaService();
    }

    @Test
    @DisplayName("возвращает food_100 для дешёвых продуктов питания")
    void returnsFood100ForCheapFood() {
        Product product = new Product();
        product.setFoodstuff(true);
        product.setPrice(BigDecimal.valueOf(80));

        String type = ReflectionTestUtils.invokeMethod(kafkaService, "determineProductType", product);

        assertThat(type).isEqualTo("food_100");
    }

    @Test
    @DisplayName("возвращает food_500 для более дорогих продуктов питания")
    void returnsFood500ForMidRangeFood() {
        Product product = new Product();
        product.setFoodstuff(true);
        product.setPrice(BigDecimal.valueOf(350));

        String type = ReflectionTestUtils.invokeMethod(kafkaService, "determineProductType", product);

        assertThat(type).isEqualTo("food_500");
    }

    @Test
    @DisplayName("возвращает not_food_1000 для непродовольственных товаров дороже 500")
    void returnsNotFood1000ForExpensiveNonFood() {
        Product product = new Product();
        product.setFoodstuff(false);
        product.setPrice(BigDecimal.valueOf(750));

        String type = ReflectionTestUtils.invokeMethod(kafkaService, "determineProductType", product);

        assertThat(type).isEqualTo("not_food_1000");
    }
}
