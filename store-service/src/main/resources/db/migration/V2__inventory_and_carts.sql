-- New ownership model; V1 legacy tables remain untouched, but have no application API.
CREATE TABLE store_scopes (
    store_id VARCHAR(64) PRIMARY KEY CHECK (store_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$')
);
INSERT INTO store_scopes VALUES ('S-1'),('S-2');

CREATE TABLE stock_receipts (
    store_id VARCHAR(64) NOT NULL REFERENCES store_scopes(store_id),
    delivery_id VARCHAR(64) NOT NULL CHECK (delivery_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$'),
    delivery_sequence BIGINT NOT NULL CHECK (delivery_sequence BETWEEN 1 AND 9007199254740991),
    fingerprint CHAR(64) NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    posted_at TIMESTAMPTZ NOT NULL CHECK (posted_at >= received_at),
    applied_at TIMESTAMPTZ NOT NULL,
    payload TEXT NOT NULL,
    PRIMARY KEY (store_id,delivery_id),
    UNIQUE (store_id,delivery_sequence)
);
CREATE TABLE inventory (
    stock_item_id UUID PRIMARY KEY,
    store_id VARCHAR(64) NOT NULL REFERENCES store_scopes(store_id),
    product_id VARCHAR(64) NOT NULL CHECK (product_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$'),
    product_type VARCHAR(8) NOT NULL CHECK (product_type IN ('FOOD','NON_FOOD')),
    short_name VARCHAR(255) NOT NULL CHECK (length(trim(short_name)) > 0),
    description VARCHAR(2000) NOT NULL,
    unit_price NUMERIC(28,2) NOT NULL CHECK (unit_price > 0),
    currency VARCHAR(3) NOT NULL CHECK (currency='RUB'),
    available_quantity INTEGER NOT NULL CHECK (available_quantity >= 0),
    last_delivery_sequence BIGINT NOT NULL CHECK (last_delivery_sequence BETWEEN 1 AND 9007199254740991),
    UNIQUE (store_id,product_id),
    UNIQUE (store_id,stock_item_id),
    FOREIGN KEY (store_id,last_delivery_sequence) REFERENCES stock_receipts(store_id,delivery_sequence)
);
CREATE TABLE processed_events (
    event_id UUID PRIMARY KEY,
    store_id VARCHAR(64) NOT NULL,
    delivery_id VARCHAR(64) NOT NULL,
    fingerprint CHAR(64) NOT NULL,
    FOREIGN KEY (store_id,delivery_id) REFERENCES stock_receipts(store_id,delivery_id)
);
CREATE TABLE incoming_goods_diagnostics (
    topic VARCHAR(255) NOT NULL,
    partition_id INTEGER NOT NULL,
    kafka_offset BIGINT NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL,
    code VARCHAR(64) NOT NULL,
    message VARCHAR(500) NOT NULL,
    raw_message TEXT,
    PRIMARY KEY (topic,partition_id,kafka_offset)
);
CREATE TABLE stock_movements (
    store_id VARCHAR(64) NOT NULL,
    delivery_id VARCHAR(64) NOT NULL,
    line_id VARCHAR(64) NOT NULL CHECK (line_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$'),
    stock_item_id UUID NOT NULL,
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    unit_price NUMERIC(28,2) NOT NULL CHECK (unit_price > 0),
    applied_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (store_id,delivery_id,line_id),
    UNIQUE (store_id,delivery_id,stock_item_id),
    FOREIGN KEY (store_id,delivery_id) REFERENCES stock_receipts(store_id,delivery_id),
    FOREIGN KEY (store_id,stock_item_id) REFERENCES inventory(store_id,stock_item_id)
);
CREATE TABLE carts (
    cart_id UUID PRIMARY KEY,
    store_id VARCHAR(64) NOT NULL REFERENCES store_scopes(store_id),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version BETWEEN 0 AND 9007199254740991),
    state VARCHAR(16) NOT NULL DEFAULT 'OPEN' CHECK (state IN ('OPEN','SUBMITTED')),
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (store_id,cart_id)
);
CREATE TABLE cart_items (
    store_id VARCHAR(64) NOT NULL,
    cart_id UUID NOT NULL,
    stock_item_id UUID NOT NULL,
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    PRIMARY KEY (cart_id,stock_item_id),
    FOREIGN KEY (store_id,cart_id) REFERENCES carts(store_id,cart_id),
    FOREIGN KEY (store_id,stock_item_id) REFERENCES inventory(store_id,stock_item_id)
);
