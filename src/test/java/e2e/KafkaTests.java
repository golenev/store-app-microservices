package e2e;

import com.experience_kafka.model.WarehouseProduct;
import com.experience_kafka.testUtil.KafkaProducerImpl;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

public class KafkaTests {


    @Test
    void test() {
        KafkaProducerImpl producer = new KafkaProducerImpl();
        producer.sendMessage("send-topic", new WarehouseProduct(212L, "Пиво", new BigDecimal("188")));
    }
}


