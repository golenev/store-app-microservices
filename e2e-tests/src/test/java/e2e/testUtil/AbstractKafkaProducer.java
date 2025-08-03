package e2e.testUtil;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

public abstract class AbstractKafkaProducer {
    private final KafkaProps config;
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final ObjectMapper mapper = new ObjectMapper();

    public AbstractKafkaProducer(KafkaProps config) {
        this.config = config;
    }

    public <T> void sendMessage(String topic, T message) {
        try {
            String json = (message instanceof String)
                    ? (String) message
                    : mapper.writeValueAsString(message);

            String key = UUID.randomUUID().toString();
            send(topic, key, json);
        } catch (Exception e) {
            logger.error("Failed to serialize message: {}", e.getMessage(), e);
        }
    }

    private void send(String topic, String key, String value) {
        try (Producer<String, String> producer = new KafkaProducer<>(config.toProperties())) {
            ProducerRecord<String, String> record = new ProducerRecord<>(topic, key, value);
            producer.send(record, (metadata, exception) -> {
                if (exception == null) {
                    logger.info("Sent to {} with key {}:\n{}", topic, key, value);
                } else {
                    logger.error("Failed to send to {}: {}", topic, exception.getMessage(), exception);
                }
            });
            producer.flush();
        }
    }
}
