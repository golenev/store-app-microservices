package com.experience_kafka.service;

import com.experience_kafka.entity.Product;
import com.experience_kafka.repository.ProductRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.client.RestTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Random;

@Service
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
    private RestTemplate restTemplate;

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
            UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl("http://localhost:6790/tariffs");
            boolean fetchAll = false; // пример: переключить для запроса всех тарифов
            if (fetchAll) {
                builder.queryParam("all", true);
            } else {
                String[] productTypes = {"food_100", "food_300"}; // пример нескольких типов
                for (String type : productTypes) {
                    builder.queryParam(type, true);
                }
            }
            String uri = builder.toUriString();
            String warehouseData = restTemplate.getForObject(uri, String.class);
            System.out.println("Received from warehouse-service: " + warehouseData);
            repo.save(p);
            System.out.println("Saved to DB: " + p);
        } catch (Exception ex) {
            System.err.println("Ошибка при разборе или сохранении: " + ex.getMessage());
        }
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
