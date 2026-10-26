-- ============================================================================
-- DDL Script 001: accounts
-- Table: accounts
-- Purpose: Core customer, merchant, and internal system balances with OCC.
-- ============================================================================

CREATE TABLE IF NOT EXISTS accounts (
    account_id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    account_type VARCHAR(32) NOT NULL,
    currency CHAR(3) NOT NULL DEFAULT 'INR',
    available_balance NUMERIC(18, 4) NOT NULL DEFAULT 0.0000,
    ledger_balance NUMERIC(18, 4) NOT NULL DEFAULT 0.0000,
    version BIGINT NOT NULL DEFAULT 1,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    tier VARCHAR(32) NOT NULL DEFAULT 'BASIC',
    shard_key INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    -- Constraints
    CONSTRAINT chk_account_currency CHECK (currency IN ('INR', 'USD', 'EUR', 'GBP', 'SGD')),
    CONSTRAINT chk_account_available_balance CHECK (available_balance >= 0.0000),
    CONSTRAINT chk_account_type CHECK (account_type IN ('SAVINGS', 'CURRENT', 'WALLET', 'MERCHANT', 'SETTLEMENT_POOL', 'FEE_REVENUE')),
    CONSTRAINT chk_account_status CHECK (status IN ('ACTIVE', 'FROZEN', 'SUSPENDED', 'CLOSED')),
    CONSTRAINT chk_account_tier CHECK (tier IN ('BASIC', 'PREMIUM', 'MERCHANT'))
);

-- Indexes
CREATE INDEX IF NOT EXISTS idx_accounts_user_id ON accounts (user_id);
CREATE INDEX IF NOT EXISTS idx_accounts_shard_key ON accounts (shard_key);
CREATE INDEX IF NOT EXISTS idx_accounts_status ON accounts (status) WHERE status != 'ACTIVE';

-- Trigger function to auto-update updated_at timestamp
CREATE OR REPLACE FUNCTION update_accounts_timestamp()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_accounts_updated_at ON accounts;
CREATE TRIGGER trg_accounts_updated_at
BEFORE UPDATE ON accounts
FOR EACH ROW EXECUTE FUNCTION update_accounts_timestamp();
