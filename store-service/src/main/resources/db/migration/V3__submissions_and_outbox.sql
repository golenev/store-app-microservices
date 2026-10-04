-- Acceptance and publication are separate states. All acceptance writes share one STORE transaction.
CREATE TABLE submissions (
    submission_id UUID PRIMARY KEY,
    store_id VARCHAR(64) NOT NULL,
    cart_id UUID NOT NULL UNIQUE,
    idempotency_key VARCHAR(128) NOT NULL CHECK (idempotency_key ~ '^[A-Za-z0-9._:-]{1,128}$'),
    request_fingerprint CHAR(64) NOT NULL,
    expected_cart_version BIGINT NOT NULL CHECK (expected_cart_version BETWEEN 0 AND 9007199254740990),
    event_id UUID NOT NULL UNIQUE,
    accepted_at TIMESTAMPTZ NOT NULL,
    cart_snapshot TEXT NOT NULL,
    UNIQUE (store_id,idempotency_key),
    UNIQUE (store_id,submission_id),
    UNIQUE (submission_id,event_id),
    FOREIGN KEY (store_id,cart_id) REFERENCES carts(store_id,cart_id)
);
CREATE TABLE stock_expenses (
    store_id VARCHAR(64) NOT NULL,
    submission_id UUID NOT NULL,
    stock_item_id UUID NOT NULL,
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    unit_price NUMERIC(28,2) NOT NULL CHECK (unit_price > 0),
    accepted_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (submission_id,stock_item_id),
    FOREIGN KEY (store_id,submission_id) REFERENCES submissions(store_id,submission_id),
    FOREIGN KEY (store_id,stock_item_id) REFERENCES inventory(store_id,stock_item_id)
);
CREATE TABLE store_outbox (
    event_id UUID PRIMARY KEY,
    submission_id UUID NOT NULL UNIQUE,
    store_id VARCHAR(64) NOT NULL,
    payload TEXT NOT NULL,
    publication_status VARCHAR(16) NOT NULL DEFAULT 'PENDING' CHECK (publication_status IN ('PENDING','PUBLISHED')),
    published_at TIMESTAMPTZ,
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    next_attempt_at TIMESTAMPTZ NOT NULL,
    last_error VARCHAR(500),
    lease_token UUID,
    lease_until TIMESTAMPTZ,
    CHECK ((publication_status='PUBLISHED') = (published_at IS NOT NULL)),
    CHECK ((lease_token IS NULL) = (lease_until IS NULL)),
    FOREIGN KEY (submission_id,event_id) REFERENCES submissions(submission_id,event_id),
    FOREIGN KEY (store_id,submission_id) REFERENCES submissions(store_id,submission_id)
);
CREATE INDEX store_outbox_due ON store_outbox(next_attempt_at,event_id) WHERE publication_status='PENDING';
