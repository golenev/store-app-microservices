package com.warehouse.controller;

import com.warehouse.entity.Tariff;
import com.warehouse.repository.TariffRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/tariffs")
public class TariffController {

    private final TariffRepository repository;

    public TariffController(TariffRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public ResponseEntity<List<Tariff>> find(@RequestParam Map<String, String> params) {
        if (params.containsKey("all") || params.isEmpty()) {
            return ResponseEntity.ok(repository.findAll());
        }
        Set<String> types = new HashSet<>(params.keySet());
        types.remove("all");
        List<Tariff> tariffs = repository.findAllById(types);
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
