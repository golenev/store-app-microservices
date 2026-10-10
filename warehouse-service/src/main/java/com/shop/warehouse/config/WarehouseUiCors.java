package com.shop.warehouse.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.*;
import java.net.URI;
import java.util.Arrays;

/** Разрешает заданным origins браузера публикацию и чтение учебных поставок без credentials. */
@Configuration
public class WarehouseUiCors implements WebMvcConfigurer {
    private final String[] origins;
    /**
     * Разбирает configured как список точных HTTP(S) origins; wildcards, credentials, path и query отклоняет
     * при запуске.
     *
     * @param configured список точных origins через запятую
     */
    public WarehouseUiCors(@Value("${shop.ui-origins:http://localhost:6789,http://127.0.0.1:6789}") String configured) {
        origins=Arrays.stream(configured.split(",")).map(String::trim).toArray(String[]::new);
        for(String origin:origins) {
            URI uri=URI.create(origin);
            if(!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost()==null
                    || uri.getUserInfo()!=null || !uri.getPath().isEmpty() || uri.getQuery()!=null || uri.getFragment()!=null)
                throw new IllegalArgumentException("shop.ui-origins must contain exact HTTP(S) origins");
        }
    }
    /**
     * Настраивает registry для POST поставщика и GET диагностики только с разрешённых origins; диагностические
     * POST браузеру не разрешает.
     *
     * @param registry реестр правил CORS Spring MVC
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/technical/deliveries").allowedOrigins(origins).allowedMethods("POST").allowedHeaders("Content-Type").maxAge(600);
        registry.addMapping("/stores/*/deliveries/**").allowedOrigins(origins).allowedMethods("GET").allowedHeaders("Content-Type").maxAge(600);
    }
}
