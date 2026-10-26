-- ============================================================================
-- DDL Script 005: transaction_events
-- Table: transaction_events
-- Purpose: Durable state machine audit log and distributed saga tracking.
-- ============================================================================

CREATE TABLE IF NOT EXISTS transaction_events (
    event_id UUID PRIMARY KEY,
    transaction_id UUID NOT NULL,
    from_state VARCHAR(32) NOT NULL,
    to_state VARCHAR(32) NOT NULL,
    event_name VARCHAR(64) NOT NULL,
    payload JSONB DEFAULT '{}',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_txn_events_txn_id ON transaction_events (transaction_id);
CREATE INDEX IF NOT EXISTS idx_txn_events_created ON transaction_events (created_at DESC);
CREATE INDEX IF NOT EXISTS idx_txn_events_name ON transaction_events (event_name);
