package com.experience_kafka.service;

import com.experience_kafka.entity.Product;
import com.experience_kafka.model.TariffDto;
import com.experience_kafka.repository.ProductRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.List;
import java.util.Random;

@Service
@Slf4j
public class KafkaService {

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private ConsumerFactory<String, String> consumerFactory;

    @Autowired
    private KafkaAdmin kafkaAdmin;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ProductRepository repo;

    @Autowired
    private WebClient warehouseWebClient;

    public void sendMessage(String topic, Product product) {
        try {
            String json = objectMapper.writeValueAsString(product);
            kafkaTemplate.send(topic, json);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Ошибка сериализации Product в JSON", e);
        }
    }

    @KafkaListener(topics = "send-topic", groupId = "warehouse-products")
    public void listen(String json) {
        sleepRandomTime();
        try {
            Product p = objectMapper.readValue(json, Product.class);
            List<TariffDto> tariffs = fetchTariffs();
            log.info("Received from warehouse-service: {}", tariffs);
            repo.save(p);
            log.info("Saved to DB: {}", p);
        } catch (Exception ex) {
            log.error("Ошибка при разборе или сохранении", ex);
            throw new RuntimeException(ex);
        }
    }

    @Retry(name = "warehouse")
    @CircuitBreaker(name = "warehouse")
    protected List<TariffDto> fetchTariffs() {
        return warehouseWebClient.get()
                .uri("/tariffs?all=true")
                .retrieve()
                .bodyToFlux(TariffDto.class)
                .timeout(Duration.ofSeconds(3))
                .collectList()
                .block();
    }


    //Имитация бизнес логики
    private void sleepRandomTime () {
        try {
            Thread.sleep(new Random().nextLong(10000, 15000));
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }
}
