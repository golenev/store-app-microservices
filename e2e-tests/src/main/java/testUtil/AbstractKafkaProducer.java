package testUtil;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;

import java.util.UUID;

public abstract class AbstractKafkaProducer {
    private final KafkaProps config;
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
        }
    }

    private void send(String topic, String key, String value) {
        try (Producer<String, String> producer = new KafkaProducer<>(config.toProperties())) {
            ProducerRecord<String, String> record = new ProducerRecord<>(topic, key, value);
            producer.send(record, (metadata, exception) -> {
            });
            producer.flush();
        }
    }
}
