package com.shop.store.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.util.Map;

/**
 * Возвращает браузеру адрес WAREHOUSE, по которому интерфейс отправляет и читает поставки.
 */
@RestController
public class UiSettingsController {
    private final String warehouseUrl;
    /**
     * Проверяет настроенный адрес WAREHOUSE: разрешены HTTP и HTTPS, запрещены имя пользователя, пароль,
     * параметры запроса и фрагмент адреса. Неверная настройка вызывает ошибку при создании контроллера.
     *
     * @param warehouseUrl HTTP-адрес WAREHOUSE для браузерного интерфейса
     */
    public UiSettingsController(@Value("${shop.warehouse-public-url:http://localhost:6791}") String warehouseUrl) {
        URI uri=URI.create(warehouseUrl);
        if(!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost()==null
                || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null)
            throw new IllegalArgumentException("shop.warehouse-public-url must be a public HTTP(S) URL without credentials/query/fragment");
        this.warehouseUrl=warehouseUrl.replaceAll("/+$","");
    }
    /**
     * Возвращает поле {@code warehouseBaseUrl} для браузерного интерфейса. Другие настройки приложения,
     * включая подключения к БД, в ответ не входят.
     *
     * @return публичный адрес WAREHOUSE для браузерного интерфейса
     */
    @GetMapping("/ui/config")
    public Map<String,String> config() { return Map.of("warehouseBaseUrl",warehouseUrl); }
}
