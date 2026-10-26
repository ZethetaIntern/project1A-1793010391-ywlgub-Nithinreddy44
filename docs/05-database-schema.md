# Database Schema Design & Indexing Strategy: PayScale HTTPE

## 1. Schema Design Principles & Correctness Guarantees

In a high-throughput financial system, database schemas must enforce structural integrity, eliminate race conditions, prevent unbacked currency creation, and comply with the **Reserve Bank of India (RBI)** digital transaction regulations.

The persistence model enforces four fundamental axioms:
1. **Double-Entry Bookkeeping:** Every financial transaction creates at least two offsetting records in `ledger_entries` (one DEBIT and one CREDIT). The platform ledger balance equation must strictly sum to zero:
   $$\sum \text{DEBIT amounts} - \sum \text{CREDIT amounts} = 0$$
2. **Dual-Balance Architecture:** Accounts maintain two independent balances:
   * `available_balance`: Checked during authorization for immediate transfers. Must satisfy `CHECK (available_balance >= 0)` (unless an explicit credit tier is granted).
   * `ledger_balance`: Settled accounting balance updated upon confirmed settlement.
3. **No Floating-Point Arithmetic:** All monetary amounts use fixed-precision `NUMERIC(18, 4)` to eliminate binary floating-point rounding errors and precision decay.
4. **Optimistic Concurrency Control (OCC):** Accounts maintain a monotonically increasing version counter (`version BIGINT NOT NULL DEFAULT 1`). Balance modifications execute via atomic Compare-And-Swap (CAS) SQL predicates, eliminating pessimistic row-level deadlocks.

---

## 2. Entity Specifications

### 2.1 `accounts`
Stores customer, merchant, and internal system account balances and statuses.
* `account_id` (UUID, PK): Primary key using UUIDv7 for time-ordered distribution.
* `user_id` (UUID, FK, NOT NULL): Associated customer or merchant profile.
* `account_type` (VARCHAR(32), NOT NULL): Enum: `SAVINGS`, `CURRENT`, `WALLET`, `MERCHANT`, `SETTLEMENT_POOL`, `FEE_REVENUE`.
* `currency` (CHAR(3), NOT NULL, DEFAULT 'INR'): ISO 4217 currency code.
* `available_balance` (NUMERIC(18, 4), NOT NULL, CHECK >= 0): Immediate spendable balance.
* `ledger_balance` (NUMERIC(18, 4), NOT NULL): Cleared book balance.
* `version` (BIGINT, NOT NULL, DEFAULT 1): OCC version counter for CAS updates.
* `status` (VARCHAR(32), NOT NULL, DEFAULT 'ACTIVE'): Enum: `ACTIVE`, `FROZEN`, `SUSPENDED`, `CLOSED`.
* `tier` (VARCHAR(32), NOT NULL, DEFAULT 'BASIC'): Enum: `BASIC` (50 TPS), `PREMIUM` (200 TPS), `MERCHANT` (1000 TPS).
* `shard_key` (INTEGER, NOT NULL): Calculated as `abs(hashtext(account_id::text)) % 4`.
* `created_at` (TIMESTAMPTZ, NOT NULL, DEFAULT NOW()): Creation timestamp.
* `updated_at` (TIMESTAMPTZ, NOT NULL, DEFAULT NOW()): Auto-updated via trigger.

### 2.2 `transactions`
Records high-level transaction state and client deduplication keys. Partitioned by month.
* `transaction_id` (UUID, PK): UUIDv7 unique transaction identifier.
* `idempotency_key` (VARCHAR(128), UNIQUE, NOT NULL): Client-supplied deduplication token (24hr TTL).
* `transaction_type` (VARCHAR(32), NOT NULL): Enum: `P2P`, `MERCHANT_PAYMENT`, `SETTLEMENT`, `REVERSAL`, `FEE`.
* `source_account_id` (UUID, FK, NULL): Sender account (NULL for system credits).
* `destination_account_id` (UUID, FK, NULL): Receiver account (NULL for fee deductions).
* `amount` (NUMERIC(18, 4), NOT NULL, CHECK > 0): Principal transaction amount.
* `currency` (CHAR(3), NOT NULL, DEFAULT 'INR'): ISO 4217 code.
* `status` (VARCHAR(32), NOT NULL): Enum: `INITIATED`, `PROCESSING`, `COMPLETED`, `FAILED`, `REVERSED`.
* `saga_id` (UUID, NOT NULL): Correlates all distributed sub-operations.
* `failure_reason` (VARCHAR(500), NULL): Detailed rejection code.
* `created_at` (TIMESTAMPTZ, NOT NULL, DEFAULT NOW()): Initiation timestamp (partition key).
* `completed_at` (TIMESTAMPTZ, NULL): Timestamp when terminal state was reached.
* `metadata` (JSONB, DEFAULT '{}'): Contextual data (IP, device fingerprint, geolocation).

### 2.3 `ledger_entries`
Immutable double-entry journal records. Partitioned by month.
* `entry_id` (UUID, PK): UUIDv7 unique journal entry ID.
* `transaction_id` (UUID, FK, NOT NULL): Associated transaction.
* `account_id` (UUID, FK, NOT NULL): Account affected by this entry.
* `entry_type` (VARCHAR(16), NOT NULL): Enum: `DEBIT` (-), `CREDIT` (+), `REVERSAL`.
* `amount` (NUMERIC(18, 4), NOT NULL, CHECK > 0): Absolute entry amount.
* `currency` (CHAR(3), NOT NULL, DEFAULT 'INR'): ISO 4217 code.
* `balance_after` (NUMERIC(18, 4), NOT NULL): Account snapshot balance after posting.
* `created_at` (TIMESTAMPTZ, NOT NULL, DEFAULT NOW()): Posting timestamp (partition key).

### 2.4 `users`
Customer profile, KYC records, and risk tiering.
* `user_id` (UUID, PK): Unique user identifier.
* `phone_number` (VARCHAR(20), UNIQUE, NOT NULL): E.164 phone number.
* `email` (VARCHAR(255), UNIQUE, NOT NULL): Customer email address.
* `full_name` (VARCHAR(255), NOT NULL): Legal name.
* `kyc_status` (VARCHAR(32), NOT NULL): Enum: `PENDING`, `VERIFIED`, `REJECTED`, `EXPIRED`.
* `kyc_tier` (VARCHAR(32), NOT NULL): Enum: `MINIMUM` (₹10,000/mo), `FULL_KYC` (₹2,00,000/mo).
* `risk_score` (INTEGER, DEFAULT 0, CHECK BETWEEN 0 AND 100): Calculated fraud score.
* `pan_hash` (CHAR(64), NULL): SHA-256 salted hash of Indian PAN card.
* `aadhaar_vault_ref` (VARCHAR(128), NULL): Tokenized reference to external Aadhaar vault.
* `created_at`, `updated_at` (TIMESTAMPTZ).

### 2.5 `transaction_events`
Immutable state transition event log for distributed sagas.
* `event_id` (UUID, PK): UUIDv7 identifier.
* `transaction_id` (UUID, FK, NOT NULL): Associated transaction.
* `from_state` (VARCHAR(32), NOT NULL): Previous status.
* `to_state` (VARCHAR(32), NOT NULL): New status.
* `event_name` (VARCHAR(64), NOT NULL): Triggering event (e.g., `SENDER_DEBITED`).
* `payload` (JSONB, DEFAULT '{}'): Event payload.
* `created_at` (TIMESTAMPTZ, NOT NULL, DEFAULT NOW()): Transition timestamp.

### 2.6 `fraud_rules`
Configurable rules evaluated by the Fraud Detection Service within the 15 ms window.
* `rule_id` (VARCHAR(64), PK): Unique rule identifier (e.g., `RULE_VELOCITY_60S`).
* `rule_name` (VARCHAR(255), NOT NULL): Human-readable title.
* `rule_type` (VARCHAR(32), NOT NULL): Enum: `VELOCITY`, `GEOLOCATION`, `BLACKLIST`, `AMOUNT_THRESHOLD`.
* `parameters` (JSONB, NOT NULL): Rule parameters (e.g., `{"max_txns": 5, "window_sec": 60}`).
* `action` (VARCHAR(32), NOT NULL): Enum: `ALLOW`, `FLAG_MANUAL_REVIEW`, `DENY`, `STEP_UP_OTP`.
* `is_active` (BOOLEAN, DEFAULT TRUE): Toggle flag.
* `created_at`, `updated_at` (TIMESTAMPTZ).

### 2.7 `merchant_settlements`
Batch settlement tracking records for partner merchants.
* `settlement_id` (UUID, PK): Unique settlement batch record ID.
* `batch_id` (VARCHAR(64), UNIQUE, NOT NULL): Scheduled batch reference.
* `merchant_id` (UUID, FK, NOT NULL): Settled merchant account.
* `transaction_count` (INTEGER, NOT NULL): Number of transactions in batch (up to 100,000).
* `gross_amount` (NUMERIC(18, 4), NOT NULL): Total transaction volume.
* `fee_amount` (NUMERIC(18, 4), NOT NULL): Platform MDR fee deduction.
* `net_amount` (NUMERIC(18, 4), NOT NULL): Net funds disbursed to merchant.
* `status` (VARCHAR(32), NOT NULL): Enum: `PENDING`, `PROCESSING`, `SETTLED`, `FAILED`.
* `settled_at` (TIMESTAMPTZ, NULL): Execution completion timestamp.

### 2.8 `notification_log`
Tracks delivery status of WebSockets, webhooks, push notifications, and SMS.
* `notification_id` (UUID, PK): Unique notification ID.
* `transaction_id` (UUID, FK, NOT NULL): Associated transaction.
* `channel` (VARCHAR(32), NOT NULL): Enum: `WEBSOCKET`, `WEBHOOK`, `SMS`, `PUSH`.
* `recipient` (VARCHAR(255), NOT NULL): Destination socket ID, URL, or phone.
* `status` (VARCHAR(32), NOT NULL): Enum: `PENDING`, `DELIVERED`, `FAILED`, `RETRYING`.
* `attempts` (INTEGER, DEFAULT 0): Retry count.
* `last_error` (VARCHAR(500), NULL): Error message.
* `created_at`, `delivered_at` (TIMESTAMPTZ).

---

## 3. Indexing Strategy & Performance Justification

| Table | Index Name | Columns | Type | Justification |
| :--- | :--- | :--- | :--- | :--- |
| `accounts` | `pk_accounts` | `account_id` | B-tree (PK) | Direct primary key lookup; sub-millisecond point queries |
| `accounts` | `idx_accounts_user_id` | `user_id` | B-tree | Fast retrieval of all accounts belonging to a customer |
| `accounts` | `idx_accounts_shard_key` | `shard_key` | B-tree | Partition routing and shard rebalancing queries |
| `transactions` | `uq_transactions_idempotency` | `idempotency_key` | B-tree (Unique) | 24-hr deduplication check; prevents duplicate payment submissions |
| `transactions` | `idx_txns_source_created` | `(source_account_id, created_at DESC)` | B-tree Composite | Accelerates customer transaction history pagination queries |
| `transactions` | `idx_txns_dest_created` | `(destination_account_id, created_at DESC)` | B-tree Composite | Fast lookup of incoming payments by date |
| `transactions` | `idx_txns_saga_id` | `saga_id` | B-tree | Enables instant correlation of all saga steps during failure recovery |
| `ledger_entries` | `idx_ledger_account_created` | `(account_id, created_at DESC)` | B-tree Composite | High-speed ledger statement generation and reconciliation audits |
| `ledger_entries` | `idx_ledger_txn_id` | `transaction_id` | B-tree | Fast retrieval of balancing debit and credit pairs for a transaction |
| `users` | `uq_users_phone` | `phone_number` | B-tree (Unique) | P2P lookup by recipient phone number |
| `users` | `uq_users_pan_hash` | `pan_hash` | B-tree (Unique) | Prevents duplicate accounts under same tax identity |
| `notification_log`| `idx_notify_pending` | `(status, created_at)` WHERE status = 'PENDING' | Partial B-tree | Extreme-speed queue polling for retry workers without table scan |

---

## 4. Time-Based Partitioning Strategy

Because PayScale processes 28M daily transactions (scaling to 85M daily), the `transactions` and `ledger_entries` tables accumulate approximately 2.5 billion rows per month. Leaving these as unpartitioned monolithic tables would cause B-tree index depths to exceed 5 levels, degrading write and search performance.

We enforce **PostgreSQL Declarative Range Partitioning by Month** on `created_at`:
```sql
CREATE TABLE transactions (
    transaction_id UUID NOT NULL,
    ...
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (transaction_id, created_at)
) PARTITION BY RANGE (created_at);

-- Monthly partition definition example
CREATE TABLE transactions_2026_10 PARTITION OF transactions
    FOR VALUES FROM ('2026-10-01 00:00:00+00') TO ('2026-11-01 00:00:00+00');
```

### Benefits:
1. **Partition Pruning:** Queries specifying a date range scan only the relevant monthly partition, ignoring hundreds of millions of historical rows.
2. **Instant Archival:** Retiring records older than the RBI-mandated 7-year retention period executes as an instant `DROP TABLE transactions_YYYY_MM`, avoiding costly vacuum and delete locks.
3. **Optimized Working Set:** Recent active partitions remain hot in OS cache and database shared buffers.
