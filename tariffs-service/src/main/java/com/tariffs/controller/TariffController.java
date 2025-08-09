package com.tariffs.controller;

import com.tariffs.entity.Tariff;
import com.tariffs.repository.TariffRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/tariffs")
@Slf4j
public class TariffController {

    private final TariffRepository repository;

    public TariffController(TariffRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public ResponseEntity<?> findAll(@RequestParam(required = false) Boolean all) {
        if (all == null || !all) {
            log.warn("Request without required parameter 'all'");
            return ResponseEntity.badRequest().body("Required parameter must be present");
        }
        var tariffs = repository.findAll();
        log.info("Returning {} tariffs", tariffs.size());
        return ResponseEntity.ok(tariffs);
    }

    @PostMapping
    public Tariff create(@RequestBody Tariff tariff) {
        return repository.save(tariff);
    }

    @PutMapping("/{productType}")
    public ResponseEntity<Tariff> update(@PathVariable String productType, @RequestBody Tariff tariff) {
        return repository.findById(productType)
                .map(existing -> {
                    existing.setMarkupCoefficient(tariff.getMarkupCoefficient());
                    return ResponseEntity.ok(repository.save(existing));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{productType}")
    public ResponseEntity<Void> delete(@PathVariable String productType) {
        if (!repository.existsById(productType)) {
            return ResponseEntity.notFound().build();
        }
        repository.deleteById(productType);
        return ResponseEntity.noContent().build();
    }
}
