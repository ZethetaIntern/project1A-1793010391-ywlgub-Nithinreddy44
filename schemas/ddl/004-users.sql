-- ============================================================================
-- DDL Script 004: users
-- Table: users
-- Purpose: Customer and merchant identity, KYC levels, and masked tax identifiers.
-- ============================================================================

CREATE TABLE IF NOT EXISTS users (
    user_id UUID PRIMARY KEY,
    phone_number VARCHAR(20) UNIQUE NOT NULL,
    email VARCHAR(255) UNIQUE NOT NULL,
    full_name VARCHAR(255) NOT NULL,
    kyc_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    kyc_tier VARCHAR(32) NOT NULL DEFAULT 'MINIMUM',
    risk_score INTEGER NOT NULL DEFAULT 0,
    pan_hash CHAR(64) NULL,
    aadhaar_vault_ref VARCHAR(128) NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    -- Constraints
    CONSTRAINT chk_users_kyc_status CHECK (kyc_status IN ('PENDING', 'VERIFIED', 'REJECTED', 'EXPIRED')),
    CONSTRAINT chk_users_kyc_tier CHECK (kyc_tier IN ('MINIMUM', 'FULL_KYC')),
    CONSTRAINT chk_users_risk_score CHECK (risk_score BETWEEN 0 AND 100)
);

CREATE INDEX IF NOT EXISTS idx_users_kyc_status ON users (kyc_status);
CREATE INDEX IF NOT EXISTS idx_users_risk_score ON users (risk_score) WHERE risk_score > 50;

-- Auto-update updated_at timestamp
CREATE OR REPLACE FUNCTION update_users_timestamp()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_users_updated_at ON users;
CREATE TRIGGER trg_users_updated_at
BEFORE UPDATE ON users
FOR EACH ROW EXECUTE FUNCTION update_users_timestamp();
