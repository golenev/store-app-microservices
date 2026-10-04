package com.shop.warehouse.delivery;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.*;
import java.net.URI;
import java.util.Arrays;

/** Allows the explicitly configured shop origins to publish/observe educational deliveries without browser credentials. */
@Configuration
public class WarehouseUiCors implements WebMvcConfigurer {
    private final String[] origins;
    /** Accepts a comma-separated exact HTTP(S) origin list; wildcards and origins with credentials/path/query are configuration errors. */
    public WarehouseUiCors(@Value("${shop.ui-origins:http://localhost:6789,http://127.0.0.1:6789}") String configured) {
        origins=Arrays.stream(configured.split(",")).map(String::trim).toArray(String[]::new);
        for(String origin:origins) {
            URI uri=URI.create(origin);
            if(!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost()==null
                    || uri.getUserInfo()!=null || !uri.getPath().isEmpty() || uri.getQuery()!=null || uri.getFragment()!=null)
                throw new IllegalArgumentException("shop.ui-origins must contain exact HTTP(S) origins");
        }
    }
    /** Limits browser access to supplier POST and delivery GET/status; business endpoints and transaction behavior remain unchanged. */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/technical/deliveries").allowedOrigins(origins).allowedMethods("POST").allowedHeaders("Content-Type").maxAge(600);
        registry.addMapping("/stores/*/deliveries/**").allowedOrigins(origins).allowedMethods("GET").allowedHeaders("Content-Type").maxAge(600);
    }
}
