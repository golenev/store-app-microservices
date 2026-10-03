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

    /** Receives the legacy percentage service retained for STORE until task 5. */
    public TariffController(TariffService service) {
        this.service = service;
    }

    /** Returns the legacy percentage array only when all=true; new quotes use /tariffs/quote. */
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

    /** Saves a legacy percentage record without changing the new tariff_rules or quote cache. */
    @PostMapping
    public Tariff create(@RequestBody Tariff tariff) {
        return service.create(tariff);
    }

    /** Updates one legacy category or returns 404; the new rule/version API is separate. */
    @PutMapping("/{productType}")
    public ResponseEntity<Tariff> update(@PathVariable String productType, @RequestBody Tariff tariff) {
        return service.update(productType, tariff)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /** Deletes one legacy category or returns 404 without touching quote snapshots. */
    @DeleteMapping("/{productType}")
    public ResponseEntity<Void> delete(@PathVariable String productType) {
        if (service.delete(productType)) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.notFound().build();
    }
}
