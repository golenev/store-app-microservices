-- Transitional schema: matches existing entities until task 5 replaces the API.
CREATE TABLE product (
    barcode_id BIGINT PRIMARY KEY,
    short_name VARCHAR(255) NOT NULL,
    description VARCHAR(255) NOT NULL,
    price NUMERIC(38,2) NOT NULL CHECK (price > 0),
    quantity INTEGER NOT NULL CHECK (quantity >= 0),
    added_at_tariffs TIMESTAMP NOT NULL,
    is_foodstuff BOOLEAN NOT NULL
);
CREATE TABLE cart (
    barcode_id BIGINT PRIMARY KEY REFERENCES product(barcode_id),
    quantity INTEGER NOT NULL CHECK (quantity > 0)
);
CREATE TABLE orders (
    id BIGINT PRIMARY KEY,
    created_at TIMESTAMP NOT NULL,
    order_sum NUMERIC(38,2) NOT NULL,
    items JSONB NOT NULL
);
