package com.shop.store.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

@Configuration
public class RestTemplateConfig {

    /**
     * Returns the synchronous HTTP client for the legacy tariff-list call.
     */
    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
}
