package com.shop.store.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.util.Map;

/** Предоставляет браузеру публичную конфигурацию подключения к WAREHOUSE. */
@RestController
public class UiSettingsController {
    private final String warehouseUrl;
    /**
     * Проверяет warehouseUrl как публичный HTTP(S) URL без credentials, query или fragment; неверная настройка
     * прерывает запуск.
     *
     * @param warehouseUrl публичный URL WAREHOUSE для браузера
     */
    public UiSettingsController(@Value("${shop.warehouse-public-url:http://localhost:6791}") String warehouseUrl) {
        URI uri=URI.create(warehouseUrl);
        if(!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost()==null
                || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null)
            throw new IllegalArgumentException("shop.warehouse-public-url must be a public HTTP(S) URL without credentials/query/fragment");
        this.warehouseUrl=warehouseUrl.replaceAll("/+$","");
    }
    /**
     * Возвращает только публичный warehouseBaseUrl для браузера; адреса БД и учётные данные в ответ не
     * включаются.
     */
    @GetMapping("/ui/config")
    public Map<String,String> config() { return Map.of("warehouseBaseUrl",warehouseUrl); }
}
