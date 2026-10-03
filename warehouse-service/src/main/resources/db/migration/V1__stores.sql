-- WAREHOUSE owns the fixed store-to-city mapping.
CREATE TABLE stores (
    store_id VARCHAR(64) PRIMARY KEY,
    city VARCHAR(64) NOT NULL CHECK (length(city) > 0)
);
