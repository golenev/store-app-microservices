-- Short database transactions own acceptance, lease fencing and result/outbox atomicity.
ALTER TABLE stores ADD COLUMN delivery_sequence BIGINT NOT NULL DEFAULT 0
    CHECK (delivery_sequence BETWEEN 0 AND 9007199254740991);

CREATE TABLE deliveries (
    store_id VARCHAR(64) NOT NULL REFERENCES stores(store_id),
    delivery_id VARCHAR(64) NOT NULL CHECK (delivery_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$'),
    delivery_sequence BIGINT NOT NULL CHECK (delivery_sequence BETWEEN 1 AND 9007199254740991),
    fingerprint CHAR(64) NOT NULL,
    state VARCHAR(20) NOT NULL CHECK (state IN ('WAITING_PRICING','POSTED','REJECTED')),
    received_at TIMESTAMPTZ NOT NULL,
    posted_at TIMESTAMPTZ,
    attempt_count BIGINT NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    next_attempt_at TIMESTAMPTZ,
    last_error JSONB,
    original_payload JSONB NOT NULL,
    lease_token UUID,
    lease_until TIMESTAMPTZ,
    PRIMARY KEY (store_id,delivery_id),
    UNIQUE (store_id,delivery_sequence),
    CHECK ((lease_token IS NULL) = (lease_until IS NULL)),
    CHECK ((state = 'POSTED') = (posted_at IS NOT NULL)),
    CHECK (posted_at IS NULL OR posted_at >= received_at),
    CHECK ((state = 'WAITING_PRICING') = (next_attempt_at IS NOT NULL)),
    CHECK (state <> 'REJECTED' OR last_error IS NOT NULL)
);
CREATE INDEX deliveries_due ON deliveries(next_attempt_at) WHERE state='WAITING_PRICING';

CREATE TABLE delivery_items (
    store_id VARCHAR(64) NOT NULL,
    delivery_id VARCHAR(64) NOT NULL,
    line_id VARCHAR(64) NOT NULL CHECK (line_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$'),
    product_id VARCHAR(64) NOT NULL CHECK (product_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$'),
    product_type VARCHAR(8) NOT NULL CHECK (product_type IN ('FOOD','NON_FOOD')),
    short_name VARCHAR(255) NOT NULL CHECK (length(trim(short_name)) > 0),
    description VARCHAR(2000) NOT NULL,
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    purchase_price NUMERIC(28,2) NOT NULL CHECK (purchase_price > 0),
    currency VARCHAR(3) NOT NULL CHECK (currency='RUB'),
    markup_rate NUMERIC(9,6),
    tariff_rule_id UUID,
    tariff_version BIGINT CHECK (tariff_version BETWEEN 1 AND 9007199254740991),
    sale_price NUMERIC(28,2) CHECK (sale_price > 0),
    PRIMARY KEY (store_id,delivery_id,line_id),
    UNIQUE (store_id,delivery_id,product_id),
    FOREIGN KEY (store_id,delivery_id) REFERENCES deliveries(store_id,delivery_id),
    CHECK ((markup_rate IS NULL AND tariff_rule_id IS NULL AND tariff_version IS NULL AND sale_price IS NULL)
        OR (markup_rate IS NOT NULL AND markup_rate >= 0 AND tariff_rule_id IS NOT NULL AND tariff_version IS NOT NULL AND sale_price IS NOT NULL))
);

CREATE TABLE received_events (
    event_id UUID PRIMARY KEY,
    store_id VARCHAR(64) NOT NULL,
    delivery_id VARCHAR(64) NOT NULL,
    fingerprint CHAR(64) NOT NULL,
    FOREIGN KEY (store_id,delivery_id) REFERENCES deliveries(store_id,delivery_id)
);

CREATE TABLE delivery_diagnostics (
    topic VARCHAR(255) NOT NULL,
    partition_id INTEGER NOT NULL,
    kafka_offset BIGINT NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL,
    code VARCHAR(64) NOT NULL,
    message VARCHAR(500) NOT NULL,
    raw_message TEXT,
    PRIMARY KEY (topic,partition_id,kafka_offset)
);

CREATE TABLE warehouse_outbox (
    event_id UUID PRIMARY KEY,
    store_id VARCHAR(64) NOT NULL,
    delivery_id VARCHAR(64) NOT NULL,
    payload TEXT NOT NULL,
    publication_status VARCHAR(16) NOT NULL DEFAULT 'PENDING' CHECK (publication_status IN ('PENDING','PUBLISHED')),
    attempt_count BIGINT NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    next_attempt_at TIMESTAMPTZ NOT NULL,
    published_at TIMESTAMPTZ,
    last_error VARCHAR(500),
    lease_token UUID,
    lease_until TIMESTAMPTZ,
    UNIQUE (store_id,delivery_id),
    FOREIGN KEY (store_id,delivery_id) REFERENCES deliveries(store_id,delivery_id),
    CHECK ((lease_token IS NULL) = (lease_until IS NULL)),
    CHECK ((publication_status='PUBLISHED') = (published_at IS NOT NULL))
);
CREATE INDEX warehouse_outbox_due ON warehouse_outbox(next_attempt_at) WHERE publication_status='PENDING';
