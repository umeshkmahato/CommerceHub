-- =====================================================================
-- ShopSphere · cartdb · V1__init_cart_schema.sql  (MySQL 8.4)
--
-- Owned by cart-service only. Carts are ephemeral shopping state —
-- high write volume, short retention. We keep unit_price as a SNAPSHOT
-- taken from product-service at add-to-cart time; the authoritative price
-- is re-validated by order-service at checkout (prices change).
-- =====================================================================

CREATE TABLE carts (
    id          BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    customer_id BIGINT UNSIGNED NOT NULL,
    status      VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    -- One ACTIVE cart per customer: the unique key makes "get or create"
    -- cart idempotent under concurrent requests (second insert fails
    -- cleanly instead of creating a duplicate cart).
    version     BIGINT      NOT NULL DEFAULT 0,
    created_at  TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at  TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                    ON UPDATE CURRENT_TIMESTAMP(6),
    expires_at  TIMESTAMP(6) NULL,   -- abandoned carts expire; cleanup job later

    CONSTRAINT uk_carts_customer UNIQUE (customer_id),
    CONSTRAINT chk_carts_status CHECK (status IN ('ACTIVE','CONVERTED','ABANDONED'))
) ENGINE = InnoDB;

CREATE TABLE cart_items (
    id             BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    cart_id        BIGINT UNSIGNED NOT NULL,
    product_id     BIGINT UNSIGNED NOT NULL,   -- logical ref to product-service; NO FK (cross-service)
    sku            VARCHAR(40)  NOT NULL,      -- denormalized for display without API call
    product_name   VARCHAR(200) NOT NULL,      -- denormalized snapshot
    unit_price     DECIMAL(12,2) NOT NULL,     -- snapshot at time of add
    quantity       INT UNSIGNED NOT NULL,
    created_at     TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at     TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                       ON UPDATE CURRENT_TIMESTAMP(6),

    -- A product appears at most once per cart; quantity updates in place.
    CONSTRAINT uk_cart_items_cart_product UNIQUE (cart_id, product_id),
    CONSTRAINT fk_cart_items_cart FOREIGN KEY (cart_id)
        REFERENCES carts (id) ON DELETE CASCADE,
    CONSTRAINT chk_cart_items_qty   CHECK (quantity > 0),
    CONSTRAINT chk_cart_items_price CHECK (unit_price >= 0)
) ENGINE = InnoDB;

-- Cart detail is always fetched by cart_id; items ride along via join.
CREATE INDEX idx_cart_items_cart ON cart_items (cart_id);
