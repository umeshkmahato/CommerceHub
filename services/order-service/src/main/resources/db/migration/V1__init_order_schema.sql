-- =====================================================================
-- ShopSphere · orderdb · V1__init_order_schema.sql  (PostgreSQL 16)
--
-- Owned by order-service. This DB carries TWO critical patterns:
--
--  1. Transactional Outbox — `outbox_events` is written in the SAME
--     transaction as the order row. A relay (OrderEventRelay) publishes
--     to Kafka AFTER commit. This guarantees: order exists ⟺ event exists.
--     Without it, a crash between DB commit and Kafka send loses events.
--
--  2. Order state machine — `status` is a VARCHAR enforced at app level:
--     PENDING → CONFIRMED → SHIPPED → DELIVERED
--             ↘ CANCELLED (from PENDING/CONFIRMED before shipment)
--     Illegal transitions are rejected in OrderService, not by the DB —
--     the state machine is business logic, and CHECK constraints can't
--     express "transition allowed", only "value allowed".
-- =====================================================================

CREATE TABLE orders (
    id              BIGSERIAL PRIMARY KEY,
    order_number    VARCHAR(30)  NOT NULL,          -- human-facing, e.g. ORD-2026-000123
    customer_id     BIGINT       NOT NULL,          -- logical ref to user-service (no FK)
    status          VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    currency        CHAR(3)      NOT NULL DEFAULT 'USD',
    subtotal        NUMERIC(12,2) NOT NULL,
    tax_amount      NUMERIC(12,2) NOT NULL DEFAULT 0,
    shipping_amount NUMERIC(12,2) NOT NULL DEFAULT 0,
    discount_amount NUMERIC(12,2) NOT NULL DEFAULT 0,
    total_amount    NUMERIC(12,2) NOT NULL,
    shipping_address JSONB       NOT NULL,          -- snapshot at order time
    -- Saga bookkeeping: which step the order saga is on, for debugging and
    -- for resuming/retrying a stuck saga.
    saga_state      VARCHAR(30)  NOT NULL DEFAULT 'INVENTORY_RESERVED_PENDING',
    version         BIGINT       NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    cancelled_at    TIMESTAMPTZ  NULL,
    cancel_reason   VARCHAR(300) NULL,

    CONSTRAINT uk_orders_number UNIQUE (order_number),
    CONSTRAINT chk_orders_status CHECK (status IN
        ('PENDING','CONFIRMED','SHIPPED','DELIVERED','CANCELLED','FAILED')),
    CONSTRAINT chk_orders_amounts CHECK (
        subtotal >= 0 AND tax_amount >= 0 AND shipping_amount >= 0
        AND discount_amount >= 0 AND total_amount >= 0
    )
);

-- "My orders" page: filter by customer, sort by recency — the hottest query.
CREATE INDEX idx_orders_customer_created
    ON orders (customer_id, created_at DESC);
-- Ops dashboard: list by status.
CREATE INDEX idx_orders_status ON orders (status);

CREATE TABLE order_items (
    id            BIGSERIAL PRIMARY KEY,
    order_id      BIGINT        NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
    product_id    BIGINT        NOT NULL,           -- logical ref to product-service
    sku           VARCHAR(40)   NOT NULL,
    product_name  VARCHAR(200)  NOT NULL,           -- snapshot: product may change later
    quantity      INT           NOT NULL,
    unit_price    NUMERIC(12,2) NOT NULL,           -- snapshot at order time
    line_total    NUMERIC(12,2) NOT NULL,
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT chk_order_items_qty CHECK (quantity > 0),
    CONSTRAINT chk_order_items_price CHECK (unit_price >= 0 AND line_total >= 0)
);
CREATE INDEX idx_order_items_order ON order_items (order_id);

-- =====================================================================
-- TRANSACTIONAL OUTBOX
-- Written atomically with the aggregate change. `published_at IS NULL`
-- rows are pending; the relay claims them, publishes, marks published.
-- `available_at` supports delayed retries without blocking the relay.
-- =====================================================================
CREATE TABLE outbox_events (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type  VARCHAR(60)  NOT NULL,          -- 'ORDER'
    aggregate_id    BIGINT       NOT NULL,          -- order id
    event_type      VARCHAR(80)  NOT NULL,          -- 'order.created'
    payload         JSONB        NOT NULL,          -- the event DTO, versioned
    occurred_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at    TIMESTAMPTZ  NULL,
    attempts        INT          NOT NULL DEFAULT 0,
    last_error      TEXT         NULL,
    available_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
-- Relay polls: WHERE published_at IS NULL ORDER BY occurred_at — partial
-- index keeps the hot query tiny as history grows.
CREATE INDEX idx_outbox_unpublished
    ON outbox_events (occurred_at)
    WHERE published_at IS NULL;
