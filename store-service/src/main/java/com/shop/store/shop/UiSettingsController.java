package com.shop.store.shop;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.util.Map;

/** Supplies browser-facing service discovery; STORE never proxies warehouse business requests or internal Docker URLs. */
@RestController
public class UiSettingsController {
    private final String warehouseUrl;
    /** Validates a public HTTP(S) base URL (optionally a proxy path) without credentials/query/fragment; invalid deployment values fail at startup. */
    public UiSettingsController(@Value("${shop.warehouse-public-url:http://localhost:6791}") String warehouseUrl) {
        URI uri=URI.create(warehouseUrl);
        if(!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost()==null
                || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null)
            throw new IllegalArgumentException("shop.warehouse-public-url must be a public HTTP(S) URL without credentials/query/fragment");
        this.warehouseUrl=warehouseUrl.replaceAll("/+$","");
    }
    /** Returns only the public warehouse base URL used by the supplier/status page; no credentials or internal DB configuration are exposed. */
    @GetMapping("/ui/config")
    public Map<String,String> config() { return Map.of("warehouseBaseUrl",warehouseUrl); }
}
