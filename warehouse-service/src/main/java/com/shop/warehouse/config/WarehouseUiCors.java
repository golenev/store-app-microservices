package com.shop.warehouse.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.*;
import java.net.URI;
import java.util.Arrays;

/**
 * Разрешает браузерному интерфейсу с заданных адресов отправлять учебные поставки и читать их состояние.
 * Настраивает разрешённые межсайтовые запросы CORS.
 */
@Configuration
public class WarehouseUiCors implements WebMvcConfigurer {
    private final String[] origins;
    /**
     * Читает разрешённые адреса интерфейса через запятую. Каждый адрес должен содержать только HTTP или HTTPS,
     * имя хоста и при необходимости порт. Имя пользователя, пароль, путь, параметры запроса и фрагмент адреса
     * отклоняет при запуске.
     *
     * @param configured разрешённые адреса браузерного интерфейса через запятую
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
     * Разрешает заданным адресам интерфейса POST для отправки поставки и GET для чтения её состояния.
     * Браузерный POST ручного повтора расчёта этими правилами не разрешён.
     *
     * @param registry реестр правил межсайтовых запросов Spring MVC
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/technical/deliveries").allowedOrigins(origins).allowedMethods("POST").allowedHeaders("Content-Type").maxAge(600);
        registry.addMapping("/stores/*/deliveries/**").allowedOrigins(origins).allowedMethods("GET").allowedHeaders("Content-Type").maxAge(600);
    }
}
