package e2e;

import com.experience_kafka.model.Product;
import com.experience_kafka.testUtil.KafkaProducerImpl;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class KafkaTests {

    @Test
    void test() {
        KafkaProducerImpl producer = new KafkaProducerImpl();
        producer.sendMessage(
                "send-topic",
                new Product(
                        212L,
                        "Водочка",
                        "Водка 0.5 л",
                        new BigDecimal("188"),
                        10,
                        LocalDateTime.now(),
                        false));
    }
}
