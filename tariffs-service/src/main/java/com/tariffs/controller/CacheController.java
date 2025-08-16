package com.tariffs.controller;

import com.tariffs.service.TariffService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
@Slf4j
public class CacheController {

    private final TariffService service;

    public CacheController(TariffService service) {
        this.service = service;
    }

    @PostMapping("/resetCache")
    public ResponseEntity<Void> resetCache(@RequestParam(required = false) Boolean now) {
        if (now == null || !now) {
            log.warn("Cache reset requested without now=true");
            return ResponseEntity.badRequest().build();
        }
        service.resetCache();
        log.info("Tariff cache reset via API");
        return ResponseEntity.ok().build();
    }
}

