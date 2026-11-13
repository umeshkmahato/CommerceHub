-- =====================================================================
-- ShopSphere · inventorydb · V2__add_outbox.sql  (MySQL 8.4)
--
-- Adds the transactional outbox to inventory-service. Why V2 and not
-- editing V1: Flyway migrations are IMMUTABLE once applied anywhere —
-- changing V1 would break checksum validation on existing databases.
-- This is itself an interview point: never edit applied migrations.
--
-- inventory.reserved / inventory.failed events drive the payment step of
-- the checkout saga, so they MUST NOT be lost — same outbox guarantee as
-- orderdb/paymentdb.
-- =====================================================================

CREATE TABLE outbox_events (
    id              CHAR(36)      PRIMARY KEY,          -- UUID as char (MySQL has no uuid type)
    aggregate_type  VARCHAR(60)   NOT NULL,             -- 'RESERVATION'
    aggregate_id    BIGINT        NOT NULL,
    event_type      VARCHAR(80)   NOT NULL,             -- 'inventory.reserved' | 'inventory.failed'
    payload         JSON          NOT NULL,
    occurred_at     TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    published_at    TIMESTAMP(6)  NULL,
    attempts        INT           NOT NULL DEFAULT 0,
    last_error      TEXT          NULL,
    available_at    TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    INDEX idx_outbox_unpublished (occurred_at, published_at)
) ENGINE = InnoDB;
