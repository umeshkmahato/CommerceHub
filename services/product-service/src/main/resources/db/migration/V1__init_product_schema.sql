-- =====================================================================
-- ShopSphere · productdb · V1__init_product_schema.sql  (MySQL 8.4)
--
-- Database-per-service: product-service is the ONLY writer/reader of
-- productdb. No other service may join against these tables — cross-service
-- reads go through the Product REST API, cross-service facts go through
-- Kafka events.
--
-- Design notes:
--  * Money is DECIMAL(12,2), NEVER float/double — binary floating point
--    cannot represent currency exactly.
--  * `status` is VARCHAR + application-level enum, not MySQL ENUM, because
--    ALTERing ENUMs requires table rebuilds and locks in production.
--  * `version` implements optimistic locking (@Version) — see Part on JPA.
--  * Composite index on (category_id, status, price) serves the most
--    common storefront query: filter by category, only active, sort by price.
-- =====================================================================

CREATE TABLE categories (
    id          BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(120)  NOT NULL,
    slug        VARCHAR(140)  NOT NULL,
    description VARCHAR(500)  NULL,
    parent_id   BIGINT UNSIGNED NULL,
    created_at  TIMESTAMP(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at  TIMESTAMP(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                  ON UPDATE CURRENT_TIMESTAMP(6),

    CONSTRAINT uk_categories_slug   UNIQUE (slug),
    -- Self-referencing FK: a category may have a parent category.
    -- ON DELETE RESTRICT prevents deleting a category that still has children.
    CONSTRAINT fk_categories_parent FOREIGN KEY (parent_id)
        REFERENCES categories (id) ON DELETE RESTRICT,
    CONSTRAINT chk_categories_no_self_parent CHECK (parent_id <> id)
) ENGINE = InnoDB;

-- Categories are read far more often than written and filtered by parent.
CREATE INDEX idx_categories_parent ON categories (parent_id);

CREATE TABLE products (
    id               BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    sku              VARCHAR(40)   NOT NULL,
    name             VARCHAR(200)  NOT NULL,
    description      TEXT          NULL,
    brand            VARCHAR(120)  NULL,
    category_id      BIGINT UNSIGNED NOT NULL,
    price            DECIMAL(12,2) NOT NULL,
    compare_at_price DECIMAL(12,2) NULL,          -- "was" price for markdown display
    cost_price       DECIMAL(12,2) NULL,          -- internal only, never exposed in DTOs
    status           VARCHAR(20)   NOT NULL DEFAULT 'DRAFT',
    image_url        VARCHAR(500)  NULL,
    attributes       JSON          NULL,          -- flexible specs: {"color":"red","size":"M"}
    version          BIGINT        NOT NULL DEFAULT 0,  -- optimistic lock
    created_at       TIMESTAMP(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at       TIMESTAMP(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                         ON UPDATE CURRENT_TIMESTAMP(6),
    deleted_at       TIMESTAMP(6)   NULL,          -- soft delete; hard deletes break audit trail

    CONSTRAINT uk_products_sku UNIQUE (sku),
    CONSTRAINT fk_products_category FOREIGN KEY (category_id)
        REFERENCES categories (id) ON DELETE RESTRICT,
    CONSTRAINT chk_products_price      CHECK (price >= 0),
    CONSTRAINT chk_products_status     CHECK (status IN
        ('DRAFT','ACTIVE','INACTIVE','DISCONTINUED'))
) ENGINE = InnoDB;

-- Storefront hot paths:
--  1. category + status filter, price sort
--  2. name search via FULLTEXT (used by /products/search?q=)
--  3. status-only admin listing
CREATE INDEX idx_products_category_status_price
    ON products (category_id, status, price);
CREATE INDEX idx_products_status ON products (status);
CREATE FULLTEXT INDEX ft_products_name_description
    ON products (name, description);

-- Product images are a separate table rather than a JSON array so each image
-- can be soft-deleted, ordered, and queried independently (1:N relationship).
CREATE TABLE product_images (
    id         BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    product_id BIGINT UNSIGNED NOT NULL,
    url        VARCHAR(500) NOT NULL,
    alt_text   VARCHAR(200) NULL,
    sort_order INT          NOT NULL DEFAULT 0,
    is_primary TINYINT(1)   NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    CONSTRAINT fk_product_images_product FOREIGN KEY (product_id)
        REFERENCES products (id) ON DELETE CASCADE
) ENGINE = InnoDB;

CREATE INDEX idx_product_images_product ON product_images (product_id, sort_order);
