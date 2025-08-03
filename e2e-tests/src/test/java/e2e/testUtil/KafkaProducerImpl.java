package e2e.testUtil;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;

public class KafkaProducerImpl extends AbstractKafkaProducer {
    public KafkaProducerImpl() {
        super(createConfig());
    }

    private static KafkaProps createConfig() {
        KafkaProps props = new KafkaProps("localhost:9092");
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        return props;
    }
}
