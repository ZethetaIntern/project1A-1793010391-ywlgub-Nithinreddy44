# Data Flow & Transaction Sequence Design: PayScale HTTPE

## 1. Overview & Data Flow Topology

In a high-throughput financial processing engine handling 12,000+ TPS, clear demarcation between **synchronous critical path** operations and **asynchronous decoupled event processing** is essential to satisfy the $p99 \le 100\text{ ms}$ latency budget while preserving strict ACID consistency and double-entry bookkeeping.

### Synchronous vs. Asynchronous Demarcation
* **Synchronous (Request-Response Path):**
  1. API Gateway authentication, rate limiting, and idempotency key reservation.
  2. Real-time pre-authorization fraud evaluation (strict 15 ms SLA).
  3. Optimistic Concurrency Control (OCC) balance verification and reservation on the sender's account.
  4. Atomic creation of the transaction record, double-entry ledger journals, and transactional outbox event.
* **Asynchronous (Event-Driven Streaming Path):**
  1. Outbox event publication to Apache Kafka (`transaction-events`).
  2. Push notifications, SMS dispatches, and WebSocket delivery to clients.
  3. Real-time analytics, ML fraud model retraining, and merchant batch settlement aggregation.
  4. Independent double-entry ledger reconciliation and regulatory compliance logging.

---

## 2. Critical Data Flow 1: P2P Payment Transaction

A Person-to-Person (P2P) payment involves transferring funds from Account A (Sender) to Account B (Receiver), potentially spanning two different database shards.

### 2.1 Happy Path Sequence
1. **Client Submission:** Client initiates `POST /api/v1/payments/p2p` with an `Idempotency-Key` header and payload:
   ```json
   {
     "source_account_id": "018d9620-1a1a-7000-8000-000000000001",
     "destination_account_id": "018d9620-2b2b-7000-8000-000000000002",
     "amount": 1500.0000,
     "currency": "INR"
   }
   ```
2. **Gateway Ingestion & Rate Limiting:** API Gateway validates JWT signature and executes Redis Lua token bucket rate check for the client's account tier.
3. **Idempotency Check:** Orchestrator queries Redis:
   * If key exists and status is `COMPLETED`: Return cached response immediately.
   * If key exists and status is `IN_FLIGHT`: Return HTTP 409 Conflict (`TRANSACTION_IN_PROGRESS`).
   * If key is new: Atomically reserve key in Redis (`SET idempotency:{key} {hash} NX EX 86400`).
4. **Fraud Evaluation (Sync, 15ms):** Orchestrator invokes Fraud Detection Service. Fraud Service checks velocity rules and risk score in Redis Feature Store. Returns `ALLOW`.
5. **Sender Debit Execution:**
   * Payment Service queries the sender's shard (Shard A) determined by `hash(source_account_id) % 4`.
   * Executes atomic OCC update on `accounts`:
     ```sql
     UPDATE accounts 
     SET available_balance = available_balance - 1500.0000,
         version = version + 1,
         updated_at = NOW()
     WHERE account_id = :sourceAccountId 
       AND version = :expectedVersion 
       AND available_balance >= 1500.0000;
     ```
   * Inserts transaction record with `status = 'PROCESSING'` and saga ID.
   * Inserts debit ledger entry: `source_account_id`, `amount = 1500.0000`, `entry_type = 'DEBIT'`.
6. **Receiver Credit Execution:**
   * Payment Service locates the receiver's shard (Shard B) determined by `hash(destination_account_id) % 4`.
   * Executes atomic credit update on `accounts`:
     ```sql
     UPDATE accounts 
     SET available_balance = available_balance + 1500.0000,
         ledger_balance = ledger_balance + 1500.0000,
         version = version + 1,
         updated_at = NOW()
     WHERE account_id = :destAccountId;
     ```
   * Inserts credit ledger entry: `destination_account_id`, `amount = 1500.0000`, `entry_type = 'CREDIT'`.
7. **Commit & Outbox Event:**
   * Sender's transaction record updated to `status = 'COMPLETED'`.
   * Sender's `ledger_balance` decremented by 1500.0000 to match `available_balance`.
   * Transactional outbox event inserted with event `PAYMENT_COMPLETED`.
   * Database transaction commits.
8. **Client Response:** API returns HTTP 200 OK with transaction receipt in under 45 ms.
9. **Async Propagation:** Debezium CDC reads outbox event and publishes to Kafka `transaction-events`. Notification Service streams status to both users via WebSockets.

### 2.2 Failure Scenario A: Insufficient Available Balance
* **Trigger:** Sender's `available_balance < 1500.0000`.
* **Behavior:** OCC `UPDATE accounts` matches 0 rows.
* **Resolution:** Transaction Orchestrator sets `status = 'FAILED'`, `failure_reason = 'INSUFFICIENT_FUNDS'`. No ledger entries created. Redis idempotency record updated to `FAILED`. Returns HTTP 422 Unprocessable Entity.

### 2.3 Failure Scenario B: Fraud Service Rejection
* **Trigger:** User initiates ₹50,000 transfer from a new device IP exceeding the 60-second velocity threshold.
* **Behavior:** Fraud Detection Service evaluates risk score as 92 (> threshold 80). Returns `DENIED: VELOCITY_EXCEEDED`.
* **Resolution:** Orchestrator records transaction with `status = 'FAILED'`, `failure_reason = 'FRAUD_SUSPECTED'`. Balance is never deducted. Audit event emitted. Returns HTTP 403 Forbidden.

### 2.4 Failure Scenario C: Receiver Credit Fails (Compensating Reversal)
* **Trigger:** Sender account debited successfully on Shard A, but Shard B experiences a transient network timeout or receiver account is discovered to be `FROZEN`.
* **Behavior:** Orchestrator catches the error.
* **Compensation Logic (within 30s SLA):**
  1. Orchestrator marks transaction `status = 'FAILED'`.
  2. Dispatches compensating transaction on Shard A:
     ```sql
     UPDATE accounts 
     SET available_balance = available_balance + 1500.0000,
         version = version + 1 
     WHERE account_id = :sourceAccountId;
     ```
  3. Inserts compensating reversal ledger entry (`entry_type = 'REVERSAL'`).
  4. Transaction final state updated to `REVERSED`.
  5. Emits `TRANSACTION_REVERSED` event to Kafka. Client receives push alert explaining reversal.

---

## 3. Critical Data Flow 2: Batch Merchant Settlement

PayScale settles accumulated merchant credits daily across thousands of partner merchants (e.g., e-commerce aggregators, retail chains), handling up to **100,000 transactions per batch**.

```
[ Settlement Scheduler (Cron) ]
            │ (Triggers Batch Settlement Job)
            ▼
[ Settlement Processing Service ] ──► Queries Unsettled Transactions from Read Replica
            │
            ▼
[ Calculates Net Merchant Totals ] ──► Merchant Net = Total Sales - MDR Fees - Refunds
            │
            ▼
[ Prepares Settlement Ledger Batch ] (Sorted by account_id to eliminate deadlocks)
            │
            ├──► 1. Debit PayScale Settlement Pool Account (Shard 0)
            │       INSERT INTO ledger_entries (pool_account, DEBIT, batch_total)
            │
            ├──► 2. Credit Merchant Settlement Accounts (Target Shards)
            │       INSERT INTO ledger_entries (merchant_account, CREDIT, net_amount)
            │       UPDATE accounts SET ledger_balance = ledger_balance + net_amount
            │
            ├──► 3. Credit PayScale Revenue Fee Account (MDR)
            │       INSERT INTO ledger_entries (fee_account, CREDIT, total_fees)
            │
            ▼
[ Updates `merchant_settlements` Record ] (Status = 'SETTLED', Batch Hash Recorded)
            │
            ▼
[ Emits `BATCH_SETTLEMENT_COMPLETED` to Kafka ]
            │
            ▼
[ Notification Service ] ──► Webhook payload delivered with HMAC-SHA256 signature to Merchant URL
```

### Settlement Deadlock Prevention
To prevent row-level deadlocks when settling 100,000 transactions across multiple merchant accounts simultaneously, the settlement engine enforces **lexicographical account ID ordering** before acquiring any database write connections. All accounts are updated in strict ascending UUID order.

---

## 4. Critical Data Flow 3: System & Database Failover Under Load

Resilience testing requires that single-component failures (e.g., PostgreSQL primary node crash) recover within the **30-second Recovery Time Objective (RTO)** with **zero loss of committed transactions (RPO = 0)**.

```
[ PostgreSQL Primary (Shard 1) ]  -- CRASHES / KILLED (Chaos Event CE-001) --
            │
            ▼ (Heartbeat lost for 5 seconds)
[ Patroni / Raft HA Controller ] 
            │
            ├── 1. Detects missing lease in DCS (Distributed Consensus Store)
            ├── 2. Issues fencing token / STONITH to guarantee old primary is dead
            ├── 3. Elects Synchronous Standby with highest WAL LSN (Zero Data Loss)
            └── 4. Promotes Standby to NEW PRIMARY (Elapsed time: ~12 seconds)
            │
            ▼
[ PgBouncer Connection Pooler ]
            │
            ├── 1. Pauses incoming client queries (buffers up to 30s)
            └── 2. Re-points backend upstream connection to NEW PRIMARY (Elapsed time: 2s)
            │
            ▼
[ Application Tier & Transaction Orchestrator ]
            │
            ├── Queries resume seamlessly through PgBouncer
            ├── In-flight uncommitted queries fail with connection reset
            ├── Idempotent Retry Loop retries uncommitted transactions with Idempotency-Key
            └── Committed transactions remain intact in Write-Ahead Log
```

* **Total Failover Time:** 14–18 seconds (well below 30-second RTO).
* **Data Loss:** 0 committed transactions (synchronous physical replication guarantees all committed WAL records exist on the promoted standby).
