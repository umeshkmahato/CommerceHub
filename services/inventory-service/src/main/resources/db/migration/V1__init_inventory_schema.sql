-- =====================================================================
-- ShopSphere · inventorydb · V1__init_inventory_schema.sql  (MySQL 8.4)
--
-- Owned by inventory-service. This is THE overselling-critical schema:
-- stock mutations must be atomic and concurrency-safe. We do NOT rely on
-- read-then-write in application code; see StockService for the locking
-- strategy (@Lock(PESSIMISTIC_WRITE) / atomic UPDATE ... WHERE guards).
--
-- `product_id` is a logical reference to product-service — deliberately
-- NO foreign key, because productdb belongs to another service.
-- =====================================================================

CREATE TABLE stock_levels (
    id                 BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    product_id         BIGINT UNSIGNED NOT NULL,
    sku                VARCHAR(40)   NOT NULL,
    warehouse_code     VARCHAR(20)   NOT NULL DEFAULT 'WH-01',
    quantity_on_hand   INT           NOT NULL DEFAULT 0,
    quantity_reserved  INT           NOT NULL DEFAULT 0,
    -- available = on_hand - reserved, computed; stored separately would
    -- risk drift. We enforce invariant via CHECK:
    quantity_available INT GENERATED ALWAYS AS
        (quantity_on_hand - quantity_reserved) STORED,
    reorder_point      INT           NOT NULL DEFAULT 5,
    version            BIGINT        NOT NULL DEFAULT 0,  -- optimistic lock
    updated_at         TIMESTAMP(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                          ON UPDATE CURRENT_TIMESTAMP(6),

    CONSTRAINT uk_stock_product_warehouse UNIQUE (product_id, warehouse_code),
    CONSTRAINT chk_stock_nonneg CHECK (
        quantity_on_hand >= 0
        AND quantity_reserved >= 0
        AND quantity_reserved <= quantity_on_hand
    )
) ENGINE = InnoDB;

-- Hot path: "is this product in stock?" by product_id alone.
CREATE INDEX idx_stock_product ON stock_levels (product_id);

-- Reservations tie reserved stock to an order so it can be released on
-- saga compensation (payment failed / order cancelled) or fulfilled later.
CREATE TABLE stock_reservations (
    id           BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    reservation_no VARCHAR(40)  NOT NULL,
    order_id     BIGINT UNSIGNED NOT NULL,   -- logical ref to order-service
    product_id   BIGINT UNSIGNED NOT NULL,
    quantity     INT UNSIGNED   NOT NULL,
    status       VARCHAR(20)    NOT NULL DEFAULT 'ACTIVE',
    expires_at   TIMESTAMP(6)   NOT NULL,    -- reservations are leases, not forever
    created_at   TIMESTAMP(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at   TIMESTAMP(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                     ON UPDATE CURRENT_TIMESTAMP(6),

    CONSTRAINT uk_reservation_no UNIQUE (reservation_no),
    -- One ACTIVE reservation per (order, product): re-delivery of the same
    -- order.created event must not double-reserve. The consumer ALSO checks
    -- processed_events for true idempotency; this constraint is the DB-level
    -- safety net.
    CONSTRAINT uk_reservation_order_product_status UNIQUE
        (order_id, product_id, status),
    CONSTRAINT chk_reservation_qty CHECK (quantity > 0),
    CONSTRAINT chk_reservation_status CHECK (status IN
        ('ACTIVE','FULFILLED','RELEASED','EXPIRED'))
) ENGINE = InnoDB;

CREATE INDEX idx_reservations_order   ON stock_reservations (order_id);
CREATE INDEX idx_reservations_expiry  ON stock_reservations (status, expires_at);
CREATE INDEX idx_reservations_product ON stock_reservations (product_id);

-- Idempotent-consumer bookkeeping: every consumed Kafka event_id is recorded
-- so redeliveries (at-least-once semantics) don't double-apply effects.
CREATE TABLE processed_events (
    event_id      VARCHAR(64)  PRIMARY KEY,   -- Kafka event UUID
    event_type    VARCHAR(80)  NOT NULL,
    processed_at  TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);
