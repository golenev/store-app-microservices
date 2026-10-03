package com.tariffs.repository;

import com.tariffs.api.TariffModels.Rule;
import com.tariffs.api.TariffModels.RuleRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** SQL access to versioned rules; transactions are owned by TariffRuleService. */
@Repository
public class TariffRuleRepository {
    private final JdbcTemplate jdbc;

    /** Receives the application datasource through Spring's transaction-aware JDBC adapter. */
    public TariffRuleRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** Returns at most two matches: the caller can distinguish no rule, one rule and ambiguity. */
    public List<Rule> matching(String productType, String cityId, String currency, BigDecimal price) {
        return jdbc.query("""
                SELECT * FROM tariff_rules WHERE product_type=? AND city_id=? AND currency=?
                AND lower_bound <= ? AND (upper_bound IS NULL OR upper_bound > ?)
                ORDER BY tariff_rule_id LIMIT 2
                """, this::map, productType, cityId, currency, price, price);
    }

    /** Reads one rule without taking a write lock; missing identifiers return an empty result. */
    public Optional<Rule> find(UUID id) {
        return jdbc.query("SELECT * FROM tariff_rules WHERE tariff_rule_id=?", this::map, id).stream().findFirst();
    }

    /** Locks an existing rule until transaction end so concurrent PUTs increment version in order. */
    public Optional<Rule> lock(UUID id) {
        return jdbc.query("SELECT * FROM tariff_rules WHERE tariff_rule_id=? FOR UPDATE", this::map, id).stream().findFirst();
    }

    /** Returns a deterministic list plus one overflow sentinel; the service never silently truncates. */
    public List<Rule> list() {
        return jdbc.query("SELECT * FROM tariff_rules ORDER BY tariff_rule_id LIMIT 1001", this::map);
    }

    /** Serializes creates in this database to enforce the contract's 1000-rule limit under concurrency. */
    public void lockCatalog() {
        jdbc.execute("SELECT pg_advisory_xact_lock(731003)");
    }

    /** Counts persisted rules in the caller's transaction after taking the catalog creation lock. */
    public long count() { return jdbc.queryForObject("SELECT count(*) FROM tariff_rules", Long.class); }

    /** Inserts a new UUID/version-1 rule; validation occurs before this transaction reaches SQL. */
    public void insert(UUID id, RuleRequest request) {
        jdbc.update("""
                INSERT INTO tariff_rules(tariff_rule_id,version,product_type,city_id,currency,lower_bound,upper_bound,markup_rate)
                VALUES(?,1,?,?,?,?,?,?)
                """, id, request.productType(), request.cityId(), request.currency(),
                new BigDecimal(request.lowerBound()), decimalOrNull(request.upperBound()), new BigDecimal(request.markupRate()));
    }

    /** Replaces a locked rule and increments its version once; deliberately leaves Redis unchanged. */
    public void replace(UUID id, RuleRequest request) {
        jdbc.update("""
                UPDATE tariff_rules SET version=version+1,product_type=?,city_id=?,currency=?,
                lower_bound=?,upper_bound=?,markup_rate=? WHERE tariff_rule_id=?
                """, request.productType(), request.cityId(), request.currency(), new BigDecimal(request.lowerBound()),
                decimalOrNull(request.upperBound()), new BigDecimal(request.markupRate()), id);
    }

    /** Deletes a rule and returns whether it existed; cached snapshots are intentionally retained. */
    public boolean delete(UUID id) { return jdbc.update("DELETE FROM tariff_rules WHERE tariff_rule_id=?", id) == 1; }

    /** Converts nullable wire bounds to exact SQL decimals without floating-point coercion. */
    private BigDecimal decimalOrNull(String value) { return value == null ? null : new BigDecimal(value); }

    /** Converts one SQL row to the strict wire format, preserving an explicit null upper bound. */
    private Rule map(ResultSet row, int rowNumber) throws SQLException {
        BigDecimal upper = row.getBigDecimal("upper_bound");
        return new Rule(row.getObject("tariff_rule_id", UUID.class), row.getLong("version"),
                row.getString("product_type"), row.getString("city_id"), row.getString("currency"),
                row.getBigDecimal("lower_bound").toPlainString(), upper == null ? null : upper.toPlainString(),
                formatRate(row.getBigDecimal("markup_rate")));
    }

    /** Normalizes a fractional rate while keeping at least two decimal digits for stable responses. */
    private String formatRate(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        return stripped.setScale(Math.max(2, stripped.scale())).toPlainString();
    }
}
