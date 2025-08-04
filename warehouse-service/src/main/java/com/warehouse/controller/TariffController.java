package com.warehouse.controller;

import com.warehouse.entity.Tariff;
import com.warehouse.repository.TariffRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/tariffs")
public class TariffController {

    private final TariffRepository repository;

    public TariffController(TariffRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<Tariff> findAll() {
        return repository.findAll();
    }

    @GetMapping("/{id}")
    public ResponseEntity<Tariff> findById(@PathVariable Long id) {
        return repository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public Tariff create(@RequestBody Tariff tariff) {
        return repository.save(tariff);
    }

    @PutMapping("/{id}")
    public ResponseEntity<Tariff> update(@PathVariable Long id, @RequestBody Tariff tariff) {
        return repository.findById(id)
                .map(existing -> {
                    existing.setProductType(tariff.getProductType());
                    existing.setPriceFrom(tariff.getPriceFrom());
                    existing.setPriceTo(tariff.getPriceTo());
                    existing.setMarkupPercentage(tariff.getMarkupPercentage());
                    return ResponseEntity.ok(repository.save(existing));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        if (!repository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        repository.deleteById(id);
        return ResponseEntity.noContent().build();
    }
}
