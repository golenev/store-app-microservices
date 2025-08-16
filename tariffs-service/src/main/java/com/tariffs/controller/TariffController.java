package com.tariffs.controller;

import com.tariffs.entity.Tariff;
import com.tariffs.service.TariffService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/tariffs")
@Slf4j
public class TariffController {

    private final TariffService service;

    public TariffController(TariffService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<?> findAll(@RequestParam(required = false) Boolean all) {
        if (all == null || !all) {
            log.warn("Request without required parameter 'all'");
            return ResponseEntity.badRequest().body("Required parameter must be present");
        }
        var tariffs = service.findAll();
        log.info("Returning {} tariffs", tariffs.size());
        return ResponseEntity.ok(tariffs);
    }

    @PostMapping
    public Tariff create(@RequestBody Tariff tariff) {
        return service.create(tariff);
    }

    @PutMapping("/{productType}")
    public ResponseEntity<Tariff> update(@PathVariable String productType, @RequestBody Tariff tariff) {
        return service.update(productType, tariff)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{productType}")
    public ResponseEntity<Void> delete(@PathVariable String productType) {
        if (service.delete(productType)) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.notFound().build();
    }
}
