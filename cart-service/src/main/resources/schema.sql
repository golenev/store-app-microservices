DROP TABLE IF EXISTS orders;
DROP TABLE IF EXISTS cart;
DROP TABLE IF EXISTS tariffs_product;

CREATE TABLE IF NOT EXISTS product (
    barcode_id BIGINT PRIMARY KEY,
    short_name VARCHAR(255) NOT NULL,
    description VARCHAR(255) NOT NULL,
    price NUMERIC(38,2) NOT NULL,
    quantity INTEGER NOT NULL,
    added_at_tariffs TIMESTAMP NOT NULL,
    is_foodstuff BOOLEAN NOT NULL
);

CREATE TABLE IF NOT EXISTS cart (
    barcode_id BIGINT PRIMARY KEY REFERENCES product(barcode_id),
    quantity INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS orders (
    id BIGINT PRIMARY KEY,
    created_at TIMESTAMP NOT NULL,
    total NUMERIC(38,2) NOT NULL,
    items TEXT NOT NULL
);
