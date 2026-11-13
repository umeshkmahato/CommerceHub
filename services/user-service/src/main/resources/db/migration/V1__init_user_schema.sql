-- =====================================================================
-- ShopSphere · userdb · V1__init_user_schema.sql  (PostgreSQL 16)
--
-- Owned by user-service only. Stores credentials, roles, refresh tokens.
-- Security invariants:
--  * password_hash is BCrypt — never store or log plaintext.
--  * refresh_tokens stores a HASH of the token, not the token itself —
--    a DB leak must not hand attackers usable sessions.
--  * email is citext-style unique via LOWER() index (case-insensitive
--    login without surprising users).
-- =====================================================================

CREATE TABLE users (
    id            BIGSERIAL PRIMARY KEY,
    email         VARCHAR(255) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,          -- BCrypt: $2a$10$...
    first_name    VARCHAR(100) NOT NULL,
    last_name     VARCHAR(100) NOT NULL,
    phone         VARCHAR(30)  NULL,
    status        VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    version       BIGINT       NOT NULL DEFAULT 0,   -- optimistic lock
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT chk_users_status CHECK (status IN ('ACTIVE','SUSPENDED','DELETED'))
);

-- Case-insensitive uniqueness on email: 'A@x.com' and 'a@x.com' collide.
CREATE UNIQUE INDEX uk_users_email_lower ON users (LOWER(email));

-- Role-based access control: many-to-many users <-> roles.
-- Kept as join table rather than ENUM so new roles need no schema change.
CREATE TABLE roles (
    id   SMALLSERIAL PRIMARY KEY,
    name VARCHAR(40) NOT NULL,
    CONSTRAINT uk_roles_name UNIQUE (name)
);

CREATE TABLE user_roles (
    user_id BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    role_id SMALLINT    NOT NULL REFERENCES roles (id) ON DELETE RESTRICT,
    PRIMARY KEY (user_id, role_id)
);

-- Shipping addresses: 1:N owned by user. JSONB would be simpler but a real
-- table lets us mark default, validate per-address, and index by country.
CREATE TABLE user_addresses (
    id           BIGSERIAL PRIMARY KEY,
    user_id      BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    label        VARCHAR(40)  NOT NULL DEFAULT 'HOME',
    recipient    VARCHAR(120) NOT NULL,
    line1        VARCHAR(200) NOT NULL,
    line2        VARCHAR(200),
    city         VARCHAR(100) NOT NULL,
    state        VARCHAR(100),
    postal_code  VARCHAR(20)  NOT NULL,
    country      VARCHAR(2)   NOT NULL,             -- ISO-3166 alpha-2
    is_default   BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),

    -- At most one default address per user — enforced by DB, not app code.
    CONSTRAINT uq_address_default_per_user
        EXCLUDE (user_id WITH =) WHERE (is_default)
);
CREATE INDEX idx_addresses_user ON user_addresses (user_id);

-- Refresh tokens: opaque random string, stored hashed, single-use rotation.
CREATE TABLE refresh_tokens (
    id           BIGSERIAL PRIMARY KEY,
    user_id      BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash   VARCHAR(128) NOT NULL,             -- SHA-256 of the token
    expires_at   TIMESTAMPTZ  NOT NULL,
    revoked_at   TIMESTAMPTZ  NULL,                 -- rotation revokes old
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_refresh_token_hash UNIQUE (token_hash)
);
CREATE INDEX idx_refresh_user ON refresh_tokens (user_id);
CREATE INDEX idx_refresh_expiry ON refresh_tokens (expires_at)
    WHERE revoked_at IS NULL;                       -- partial index: only live tokens

-- Seed roles (Flyway repeatable-safe: INSERT ... ON CONFLICT DO NOTHING).
INSERT INTO roles (name) VALUES ('CUSTOMER'), ('ADMIN')
ON CONFLICT (name) DO NOTHING;
