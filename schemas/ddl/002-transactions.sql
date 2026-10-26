-- ============================================================================
-- DDL Script 002: transactions
-- Table: transactions (Declaratively Partitioned by Month)
-- Purpose: Records financial transaction status, idempotency, and saga linkage.
-- ============================================================================

CREATE TABLE IF NOT EXISTS transactions (
    transaction_id UUID NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    transaction_type VARCHAR(32) NOT NULL,
    source_account_id UUID NULL,
    destination_account_id UUID NULL,
    amount NUMERIC(18, 4) NOT NULL,
    currency CHAR(3) NOT NULL DEFAULT 'INR',
    status VARCHAR(32) NOT NULL DEFAULT 'INITIATED',
    saga_id UUID NOT NULL,
    failure_reason VARCHAR(500) NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at TIMESTAMPTZ NULL,
    metadata JSONB DEFAULT '{}',

    -- Composite Primary Key required for PostgreSQL Declarative Partitioning
    PRIMARY KEY (transaction_id, created_at),

    -- Constraints
    CONSTRAINT chk_transaction_amount CHECK (amount > 0.0000),
    CONSTRAINT chk_transaction_type CHECK (transaction_type IN ('P2P', 'MERCHANT_PAYMENT', 'SETTLEMENT', 'REVERSAL', 'FEE')),
    CONSTRAINT chk_transaction_status CHECK (status IN ('INITIATED', 'PROCESSING', 'COMPLETED', 'FAILED', 'REVERSED'))
) PARTITION BY RANGE (created_at);

-- Unique index on idempotency_key must include partition key in partitioned tables
CREATE UNIQUE INDEX IF NOT EXISTS uq_txns_idempotency_key_created 
ON transactions (idempotency_key, created_at);

-- Performance Indexes
CREATE INDEX IF NOT EXISTS idx_txns_source_created 
ON transactions (source_account_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_txns_dest_created 
ON transactions (destination_account_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_txns_saga_id 
ON transactions (saga_id);

CREATE INDEX IF NOT EXISTS idx_txns_status 
ON transactions (status) WHERE status IN ('INITIATED', 'PROCESSING');

-- Sample Initial Monthly Partitions
CREATE TABLE IF NOT EXISTS transactions_2026_10 PARTITION OF transactions
    FOR VALUES FROM ('2026-10-01 00:00:00+00') TO ('2026-11-01 00:00:00+00');

CREATE TABLE IF NOT EXISTS transactions_2026_11 PARTITION OF transactions
    FOR VALUES FROM ('2026-11-01 00:00:00+00') TO ('2026-12-01 00:00:00+00');
