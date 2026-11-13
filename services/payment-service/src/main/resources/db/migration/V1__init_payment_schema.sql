-- =====================================================================
-- ShopSphere · paymentdb · V1__init_payment_schema.sql  (PostgreSQL 16)
--
-- Owned by payment-service. Simulates a payment gateway (PSP) — we NEVER
-- store raw card PAN/CVV. `gateway_ref` is the PSP's tokenized reference.
--
-- Two hard requirements live here:
--  1. Idempotency — `idempotency_key` UNIQUE. Retries/replays of the same
--     charge must return the original payment, never double-charge.
--  2. Outbox — payment.completed / payment.failed events ride the same
--     atomic-write pattern as order-service.
-- =====================================================================

CREATE TABLE payments (
    id               BIGSERIAL PRIMARY KEY,
    payment_number   VARCHAR(30)  NOT NULL,         -- PAY-2026-000456
    order_id         BIGINT       NOT NULL,         -- logical ref to order-service
    customer_id      BIGINT       NOT NULL,         -- logical ref to user-service
    amount           NUMERIC(12,2) NOT NULL,
    currency         CHAR(3)      NOT NULL DEFAULT 'USD',
    status           VARCHAR(20)  NOT NULL DEFAULT 'INITIATED',
    -- Client/saga supplies this; unique constraint = exactly-once effect
    -- even under Kafka redelivery or HTTP retries.
    idempotency_key  VARCHAR(80)  NOT NULL,
    gateway_ref      VARCHAR(80)  NULL,             -- simulated PSP reference
    failure_reason   VARCHAR(300) NULL,
    version          BIGINT       NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    completed_at     TIMESTAMPTZ  NULL,

    CONSTRAINT uk_payments_number UNIQUE (payment_number),
    CONSTRAINT uk_payments_idempotency UNIQUE (idempotency_key),
    CONSTRAINT chk_payments_status CHECK (status IN
        ('INITIATED','AUTHORIZED','CAPTURED','FAILED','REFUNDED','PARTIALLY_REFUNDED')),
    CONSTRAINT chk_payments_amount CHECK (amount > 0)
);

-- One payment attempt chain per order for now (a retry creates a new
-- payment row with a new idempotency key only if explicitly requested).
CREATE INDEX idx_payments_order    ON payments (order_id);
CREATE INDEX idx_payments_customer ON payments (customer_id, created_at DESC);

-- Append-only transaction ledger: every auth/capture/refund/void against a
-- payment. Never UPDATE rows here — corrections are new compensating rows.
CREATE TABLE payment_transactions (
    id            BIGSERIAL PRIMARY KEY,
    payment_id    BIGINT        NOT NULL REFERENCES payments (id) ON DELETE RESTRICT,
    txn_type      VARCHAR(20)   NOT NULL,           -- AUTH, CAPTURE, REFUND, VOID
    amount        NUMERIC(12,2) NOT NULL,
    status        VARCHAR(20)   NOT NULL,           -- SUCCESS, FAILED, PENDING
    gateway_ref   VARCHAR(80)   NULL,
    idempotency_key VARCHAR(80) NULL,
    failure_reason VARCHAR(300) NULL,
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT chk_txn_type CHECK (txn_type IN ('AUTH','CAPTURE','REFUND','VOID')),
    CONSTRAINT chk_txn_status CHECK (status IN ('SUCCESS','FAILED','PENDING')),
    CONSTRAINT chk_txn_amount CHECK (amount > 0)
);
CREATE INDEX idx_txns_payment ON payment_transactions (payment_id);
CREATE UNIQUE INDEX uk_txns_idempotency
    ON payment_transactions (idempotency_key) WHERE idempotency_key IS NOT NULL;

-- Same outbox pattern as orderdb — see V1__init_order_schema.sql comments.
CREATE TABLE outbox_events (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type  VARCHAR(60)  NOT NULL,          -- 'PAYMENT'
    aggregate_id    BIGINT       NOT NULL,
    event_type      VARCHAR(80)  NOT NULL,          -- 'payment.completed'
    payload         JSONB        NOT NULL,
    occurred_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at    TIMESTAMPTZ  NULL,
    attempts        INT          NOT NULL DEFAULT 0,
    last_error      TEXT         NULL,
    available_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_outbox_unpublished
    ON outbox_events (occurred_at)
    WHERE published_at IS NULL;

-- Idempotent consumer table for events this service consumes
-- (e.g. order.cancelled → auto-refund).
CREATE TABLE processed_events (
    event_id     VARCHAR(64) PRIMARY KEY,
    event_type   VARCHAR(80) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
