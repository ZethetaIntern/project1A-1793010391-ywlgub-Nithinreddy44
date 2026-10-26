-- ============================================================================
-- DDL Script 007: merchant_settlements
-- Table: merchant_settlements
-- Purpose: Records batch settlement cycles (up to 100,000 txns) and payouts.
-- ============================================================================

CREATE TABLE IF NOT EXISTS merchant_settlements (
    settlement_id UUID PRIMARY KEY,
    batch_id VARCHAR(64) UNIQUE NOT NULL,
    merchant_id UUID NOT NULL,
    transaction_count INTEGER NOT NULL,
    gross_amount NUMERIC(18, 4) NOT NULL,
    fee_amount NUMERIC(18, 4) NOT NULL,
    net_amount NUMERIC(18, 4) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    settled_at TIMESTAMPTZ NULL,

    CONSTRAINT chk_settlement_amounts CHECK (gross_amount >= 0.0000 AND fee_amount >= 0.0000 AND net_amount >= 0.0000),
    CONSTRAINT chk_settlement_status CHECK (status IN ('PENDING', 'PROCESSING', 'SETTLED', 'FAILED'))
);

CREATE INDEX IF NOT EXISTS idx_settlements_merchant ON merchant_settlements (merchant_id);
CREATE INDEX IF NOT EXISTS idx_settlements_status ON merchant_settlements (status);
CREATE INDEX IF NOT EXISTS idx_settlements_created ON merchant_settlements (created_at DESC);
