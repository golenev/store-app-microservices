package com.experience_kafka.service;

import com.experience_kafka.model.WarehouseProduct;
import com.experience_kafka.repository.WarehouseProductRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;

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
    private WarehouseProductRepository repo;

    public void sendMessage(String topic, WarehouseProduct product) {
        try {
            String json = objectMapper.writeValueAsString(product);
            kafkaTemplate.send(topic, json);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Ошибка сериализации WarehouseProduct в JSON", e);
        }
    }

    @KafkaListener(topics = "send-topic", groupId = "warehouse-products")
    public void listen(String json) {
        try {
            WarehouseProduct p = objectMapper.readValue(json, WarehouseProduct.class);
            repo.save(p);
            System.out.println("Saved to DB: " + p);
        } catch (Exception ex) {
            System.err.println("Ошибка при разборе или сохранении: " + ex.getMessage());
        }
    }

}
