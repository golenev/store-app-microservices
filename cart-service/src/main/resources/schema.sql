DROP TABLE IF EXISTS cart;
DROP TABLE IF EXISTS warehouse_product;

CREATE TABLE IF NOT EXISTS product (
    barcode_id BIGINT PRIMARY KEY,
    short_name VARCHAR(255) NOT NULL,
    description VARCHAR(255) NOT NULL,
    price NUMERIC(38,2) NOT NULL,
    quantity INTEGER NOT NULL,
    added_at_warehouse TIMESTAMP NOT NULL,
    is_foodstuff BOOLEAN NOT NULL
);
