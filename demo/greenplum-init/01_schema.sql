-- E-commerce operational schema for the demo stand.
-- Loaded automatically on first start of the greenplum container.
-- Distribution clauses are commented out to keep the file portable
-- between PostgreSQL (docker stand) and real Greenplum 7.

CREATE SCHEMA IF NOT EXISTS ops;
SET search_path TO ops, public;

CREATE TABLE IF NOT EXISTS customers (
    id          BIGINT PRIMARY KEY,
    name        TEXT NOT NULL,
    email       TEXT NOT NULL UNIQUE,
    country     CHAR(2) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
)
-- DISTRIBUTED BY (id)        -- Greenplum
;

CREATE TABLE IF NOT EXISTS products (
    sku         TEXT PRIMARY KEY,
    name        TEXT NOT NULL,
    category    TEXT NOT NULL,
    brand       TEXT NOT NULL,
    price       NUMERIC(10,2) NOT NULL
)
-- DISTRIBUTED REPLICATED     -- Greenplum
;

CREATE TABLE IF NOT EXISTS orders (
    id          BIGINT PRIMARY KEY,
    customer_id BIGINT NOT NULL REFERENCES customers(id),
    status      TEXT NOT NULL,
    total       NUMERIC(12,2) NOT NULL,
    ordered_at  TIMESTAMPTZ NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
)
-- DISTRIBUTED BY (customer_id)
;

CREATE INDEX IF NOT EXISTS idx_orders_updated_at ON orders (updated_at);

CREATE TABLE IF NOT EXISTS order_items (
    order_id    BIGINT NOT NULL REFERENCES orders(id),
    product_sku TEXT NOT NULL REFERENCES products(sku),
    qty         INT NOT NULL CHECK (qty > 0),
    line_total  NUMERIC(12,2) NOT NULL,
    PRIMARY KEY (order_id, product_sku)
)
-- DISTRIBUTED BY (order_id)
;

-- Multi-tenant marker: extra column on customers to demonstrate
-- mapping parameterization via ${tenant.id} in NiFi expressions.
ALTER TABLE customers ADD COLUMN IF NOT EXISTS tenant_id TEXT NOT NULL DEFAULT 'acme';
