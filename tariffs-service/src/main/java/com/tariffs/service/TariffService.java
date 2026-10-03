package com.tariffs.service;

import com.tariffs.entity.Tariff;
import com.tariffs.repository.TariffRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Optional;

/** Transitional percentage API for the old STORE consumer; independent of the new rule/quote model. */
@Service
public class TariffService {
    private final TariffRepository repository;
    private final TariffQuoteService quotes;

    /** Receives legacy persistence and the quote reset used by the temporary legacy reset alias. */
    public TariffService(TariffRepository repository, TariffQuoteService quotes) {
        this.repository = repository;
        this.quotes = quotes;
    }

    /** Reads legacy percentages directly from PostgreSQL without an artificial delay or separate cache. */
    public List<Tariff> findAll() { return repository.findAll(); }

    /** Saves a legacy percentage without affecting new rule records or quote snapshots. */
    public Tariff create(Tariff tariff) { return repository.save(tariff); }

    /** Updates one legacy percentage in a transaction; a missing category returns an empty result. */
    @Transactional
    public Optional<Tariff> update(String productType, Tariff tariff) {
        return repository.findById(productType).map(existing -> {
            existing.setMarkupCoefficient(tariff.getMarkupCoefficient());
            return repository.save(existing);
        });
    }

    /** Removes an existing legacy percentage or reports its absence; new quote rules are independent. */
    @Transactional
    public boolean delete(String productType) {
        if (!repository.existsById(productType)) return false;
        repository.deleteById(productType);
        return true;
    }

    /** Keeps the old HTTP reset alias operational, using the new namespace-scoped reset and failure semantics. */
    public void resetCache() { quotes.reset(); }
}
