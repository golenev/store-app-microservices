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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
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
    private RestTemplate restTemplate;

    @Value("${tariffs-service.base-url}")
    private String tariffsServiceBaseUrl;

    public void sendMessage(String topic, Product product) {
        try {
            String json = objectMapper.writeValueAsString(product);
            kafkaTemplate.send(topic, json);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Ошибка сериализации Product в JSON", e);
        }
    }

    @KafkaListener(topics = "send-topic", groupId = "tariffs-products")
    public void listen(String json) {
        sleepRandomTime();
        try {
            Product p = objectMapper.readValue(json, Product.class);
            List<TariffDto> tariffs = fetchTariffs();
            log.info("Received from tariffs-service: {}", tariffs);
            applyTariff(p, tariffs);
            repo.save(p);
            log.info("Saved to DB: {}", p);
        } catch (Exception ex) {
            log.error("Ошибка при разборе или сохранении", ex);
            throw new RuntimeException(ex);
        }
    }

    private void applyTariff(Product product, List<TariffDto> tariffs) {
        String productType = determineProductType(product);
        TariffDto tariff = tariffs.stream()
                .filter(t -> t.getProductType().equals(productType))
                .findFirst()
                .orElse(null);
        if (tariff != null) {
            BigDecimal coefficient = tariff.getMarkupCoefficient();
            BigDecimal price = product.getPrice();
            BigDecimal newPrice = price.add(
                    price.multiply(coefficient).divide(BigDecimal.valueOf(100))
            );
            product.setPrice(newPrice);
            log.info("получен товар из кафки, присвоена тип товара {} с наценкой {}", productType, coefficient);
        } else {
            log.warn("получен товар из кафки, тариф для типа {} не найден", productType);
        }
    }

    private String determineProductType(Product product) {
        BigDecimal price = product.getPrice();
        if (product.isFoodstuff()) {
            if (price.compareTo(BigDecimal.valueOf(100)) <= 0) {
                return "food_100";
            } else if (price.compareTo(BigDecimal.valueOf(300)) <= 0) {
                return "food_300";
            } else if (price.compareTo(BigDecimal.valueOf(500)) <= 0) {
                return "food_500";
            } else {
                return "food_1000";
            }
        } else {
            if (price.compareTo(BigDecimal.valueOf(100)) <= 0) {
                return "not_food_100";
            } else if (price.compareTo(BigDecimal.valueOf(500)) <= 0) {
                return "not_food_500";
            } else {
                return "not_food_1000";
            }
        }
    }

    @Retry(name = "tariffs") // повторные попытки при ошибках согласно настройкам Retry "tariffs"
    @CircuitBreaker(name = "tariffs") // предотвращает каскадные сбои, открывая выключатель при частых ошибках
    protected List<TariffDto> fetchTariffs() {
        String url = tariffsServiceBaseUrl + "/tariffs?all=true"; // полный URL сервиса тарифов
        ResponseEntity<List<TariffDto>> response = restTemplate.exchange(
                url, // адрес запроса
                HttpMethod.GET, // HTTP-метод
                null, // без тела запроса
                new ParameterizedTypeReference<List<TariffDto>>() {} // тип ожидаемого ответа
        ); // выполняем HTTP-запрос
        return response.getBody(); // возвращаем список тарифов из ответа
    }


    //Имитация бизнес логики
    private void sleepRandomTime () {
        try {
            Thread.sleep(new Random().nextLong(5000, 11000));
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }
}
