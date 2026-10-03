CREATE TABLE tariff_rules (
    tariff_rule_id UUID PRIMARY KEY,
    version BIGINT NOT NULL DEFAULT 1 CHECK (version BETWEEN 1 AND 9007199254740991),
    product_type VARCHAR(8) NOT NULL CHECK (product_type IN ('FOOD','NON_FOOD')),
    city_id VARCHAR(64) NOT NULL CHECK (city_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$'),
    currency VARCHAR(3) NOT NULL CHECK (currency = 'RUB'),
    lower_bound NUMERIC(28,2) NOT NULL CHECK (lower_bound >= 0),
    upper_bound NUMERIC(28,2),
    markup_rate NUMERIC(9,6) NOT NULL CHECK (markup_rate >= 0),
    CHECK (upper_bound IS NULL OR upper_bound > lower_bound)
);
CREATE INDEX tariff_rules_lookup ON tariff_rules(product_type,city_id,currency,lower_bound);
-- Overlaps are intentionally permitted and diagnosed by quote as TARIFF_AMBIGUOUS.
