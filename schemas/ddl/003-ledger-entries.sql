-- ============================================================================
-- DDL Script 003: ledger_entries
-- Table: ledger_entries (Declaratively Partitioned by Month)
-- Purpose: Immutable double-entry bookkeeping journal.
-- ============================================================================

CREATE TABLE IF NOT EXISTS ledger_entries (
    entry_id UUID NOT NULL,
    transaction_id UUID NOT NULL,
    account_id UUID NOT NULL,
    entry_type VARCHAR(16) NOT NULL,
    amount NUMERIC(18, 4) NOT NULL,
    currency CHAR(3) NOT NULL DEFAULT 'INR',
    balance_after NUMERIC(18, 4) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    -- Composite Primary Key for Partitioning
    PRIMARY KEY (entry_id, created_at),

    -- Constraints
    CONSTRAINT chk_ledger_amount CHECK (amount > 0.0000),
    CONSTRAINT chk_ledger_entry_type CHECK (entry_type IN ('DEBIT', 'CREDIT', 'REVERSAL'))
) PARTITION BY RANGE (created_at);

-- Performance Indexes
CREATE INDEX IF NOT EXISTS idx_ledger_account_created 
ON ledger_entries (account_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_ledger_txn_id 
ON ledger_entries (transaction_id);

CREATE INDEX IF NOT EXISTS idx_ledger_type_amount 
ON ledger_entries (entry_type, amount);

-- Sample Monthly Partitions
CREATE TABLE IF NOT EXISTS ledger_entries_2026_10 PARTITION OF ledger_entries
    FOR VALUES FROM ('2026-10-01 00:00:00+00') TO ('2026-11-01 00:00:00+00');

CREATE TABLE IF NOT EXISTS ledger_entries_2026_11 PARTITION OF ledger_entries
    FOR VALUES FROM ('2026-11-01 00:00:00+00') TO ('2026-12-01 00:00:00+00');
