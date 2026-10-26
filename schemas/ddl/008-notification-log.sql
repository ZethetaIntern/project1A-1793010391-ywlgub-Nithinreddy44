-- ============================================================================
-- DDL Script 008: notification_log
-- Table: notification_log
-- Purpose: Real-time notification delivery tracking across WebSockets, Webhooks, Push, and SMS.
-- ============================================================================

CREATE TABLE IF NOT EXISTS notification_log (
    notification_id UUID PRIMARY KEY,
    transaction_id UUID NOT NULL,
    channel VARCHAR(32) NOT NULL,
    recipient VARCHAR(255) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    attempts INTEGER NOT NULL DEFAULT 0,
    last_error VARCHAR(500) NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    delivered_at TIMESTAMPTZ NULL,

    CONSTRAINT chk_notify_channel CHECK (channel IN ('WEBSOCKET', 'WEBHOOK', 'SMS', 'PUSH')),
    CONSTRAINT chk_notify_status CHECK (status IN ('PENDING', 'DELIVERED', 'FAILED', 'RETRYING'))
);

CREATE INDEX IF NOT EXISTS idx_notify_pending 
ON notification_log (status, created_at) 
WHERE status = 'PENDING';

CREATE INDEX IF NOT EXISTS idx_notify_txn_id 
ON notification_log (transaction_id);
