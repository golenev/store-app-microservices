package e2e.testUtil;


import org.apache.kafka.clients.producer.ProducerConfig;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

public class KafkaProps {
    private final String bootstrapServers;
    private final Map<String, Object> properties = new HashMap<>();

    public KafkaProps(String bootstrapServers) {
        this.bootstrapServers = bootstrapServers;
    }

    public void put(String key, Object value) {
        properties.put(key, value);
    }

    public Properties toProperties() {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.putAll(properties);
        return props;
    }
}
