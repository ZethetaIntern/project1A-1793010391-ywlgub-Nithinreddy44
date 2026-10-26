-- ============================================================================
-- DDL Script 006: fraud_rules
-- Table: fraud_rules
-- Purpose: Configurable rules evaluated in real-time (15ms SLA) by Fraud Service.
-- ============================================================================

CREATE TABLE IF NOT EXISTS fraud_rules (
    rule_id VARCHAR(64) PRIMARY KEY,
    rule_name VARCHAR(255) NOT NULL,
    rule_type VARCHAR(32) NOT NULL,
    parameters JSONB NOT NULL,
    action VARCHAR(32) NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_rule_type CHECK (rule_type IN ('VELOCITY', 'GEOLOCATION', 'BLACKLIST', 'AMOUNT_THRESHOLD')),
    CONSTRAINT chk_rule_action CHECK (action IN ('ALLOW', 'FLAG_MANUAL_REVIEW', 'DENY', 'STEP_UP_OTP'))
);

CREATE INDEX IF NOT EXISTS idx_fraud_rules_active ON fraud_rules (is_active) WHERE is_active = TRUE;

-- Seed Standard Production Fraud Rules
INSERT INTO fraud_rules (rule_id, rule_name, rule_type, parameters, action, is_active)
VALUES 
('RULE_VELOCITY_60S', 'Max 5 transactions in 60 seconds', 'VELOCITY', '{"max_txns": 5, "window_seconds": 60}'::jsonb, 'DENY', TRUE),
('RULE_HIGH_VALUE_OTP', 'Transactions above 50,000 INR require step-up verification', 'AMOUNT_THRESHOLD', '{"threshold_amount": 50000.00}'::jsonb, 'STEP_UP_OTP', TRUE),
('RULE_RAPID_DRAIN', 'Withdrawal of over 80% available balance within 5 minutes', 'AMOUNT_THRESHOLD', '{"percentage": 80.0, "window_seconds": 300}'::jsonb, 'FLAG_MANUAL_REVIEW', TRUE)
ON CONFLICT (rule_id) DO NOTHING;
