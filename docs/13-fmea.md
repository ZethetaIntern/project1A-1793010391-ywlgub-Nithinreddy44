# Failure Mode and Effects Analysis (FMEA): PayScale HTTPE

## 1. FMEA Framework & Scoring Methodology

To ensure institutional reliability under 12,000 sustained and 18,000 burst TPS, we conduct a systematic **Failure Mode and Effects Analysis (FMEA)** across application services, persistent data stores, messaging infrastructure, network topology, and operational procedures.

### Scoring Criteria (1–10 Scale):
* **Severity (S):** Impact on business operations, financial data integrity, and compliance.
  * *10:* Unrecoverable data loss, double-spend anomaly, regulatory violation (RBI censure).
  * *7–9:* Complete system downtime, transaction processing halted, severe latency spike.
  * *4–6:* Degraded performance, single component failover, minor non-financial feature impaired.
  * *1–3:* Minor cosmetic issue, background log delay, no user impact.
* **Occurrence (O):** Probability or frequency of failure mode occurring in production.
  * *10:* Inevitable daily event under load (> 10 times / day).
  * *7–9:* Frequent weekly occurrence.
  * *4–6:* Occasional monthly occurrence or rare race condition.
  * *1–3:* Highly improbable hardware or catastrophic physical disaster.
* **Detection (D):** Likelihood of the system detecting the failure before customer/ledger impact.
  * *10:* Completely undetectable; silent corruption.
  * *7–9:* Detected only after customer complaints or reconciliation audit drift.
  * *4–6:* Detected via standard health checks and automated log scanners within minutes.
  * *1–3:* Instantly detected by automated circuit breakers, synthetic probes, or database constraints.

$$\text{Risk Priority Number (RPN)} = \text{Severity (S)} \times \text{Occurrence (O)} \times \text{Detection (D)}$$
*Any failure mode with $\text{RPN} > 200$ requires mandatory immediate architectural mitigation.*

---

## 2. Comprehensive FMEA Matrix (25 Failure Modes)

| ID | Failure Mode | Root Cause / Mechanism | Failure Effect | S | O | D | RPN | Mitigation Strategy & Architecture Defense |
| :--- | :--- | :--- | :--- | :---: | :---: | :---: | :---: | :--- |
| **FM-001** | Primary DB Node Hardware Failure | Kernel panic, EBS volume failure, power loss | All writes to that shard fail; in-flight txns abort | 8 | 4 | 2 | **64** | Patroni Raft auto-failover to synchronous standby within 30s; PgBouncer query pause buffer; zero committed data loss (RPO = 0) |
| **FM-002** | Kafka Broker Failure (1 of 3) | Node termination, OOM killer, disk failure | Temporary partition leadership unavailability | 5 | 5 | 2 | **50** | `replication.factor = 3`, `min.insync.replicas = 2`; KRaft re-elects partition leaders in < 3s; producers retry with `acks=all` |
| **FM-003** | Redis Cluster Master Node Crash | Hardware reboot, segfault, memory fragmentation | Cache miss spike; read load diverts to database | 6 | 4 | 2 | **48** | Redis Sentinel / Cluster automated replica promotion within 5s; Circuit breaker `CB-CACHE` prevents DB connection storm |
| **FM-004** | Inter-AZ Network Partition | Fiber cut, cross-AZ routing table failure | High replication lag, potential split-brain | 9 | 3 | 4 | **108** | Monotonic fencing tokens prevent split-brain writes; Raft quorum writes require 2 of 3 AZs; client AZ-affinity routing |
| **FM-005** | Deadlock in `accounts` Table | Circular row locking in concurrent P2P transfers | Transactions timeout (`40P01`) and fail | 4 | 6 | 3 | **72** | Optimistic Concurrency Control (OCC) with atomic Compare-And-Swap (CAS) completely eliminates row locks; bounded retry loop |
| **FM-006** | Fraud Detection Service Timeout | ML model inference latency spike, GC pause | Payment processing halted; p99 latency SLA breached | 6 | 5 | 2 | **60** | Circuit breaker `CB-FRAUD` with 15s reset; fallback auto-approves txns < ₹5,000 with `MANUAL_REVIEW_FLAGGED`; OTP for > ₹5,000 |
| **FM-007** | Idempotency Key Collision | Hash collision or bad client UUID generator | Legitimate transaction falsely rejected as duplicate | 7 | 2 | 5 | **70** | UUIDv7 with client payload SHA-256 fingerprint validation; mismatched payloads return HTTP 409 Conflict with audit alert |
| **FM-008** | Kafka Storage Disk Full | Retention misconfiguration, sudden burst | Broker rejects produces; pipeline blocks | 9 | 3 | 2 | **54** | Storage alerts at 70% and 85% capacity; automatic segment deletion and log compaction on `transaction-events` |
| **FM-009** | mTLS Certificate Expiration | Automation script failure, cert authority drift | All inter-microservice communication blocked | 10 | 2 | 3 | **60** | Automated cert-manager rotation 30 days prior to expiry; Prometheus certificate expiration gauges alert at T-14 days |
| **FM-010** | Memory Leak in Orchestrator | Unbounded saga state caching, stream leak | Gradual OOM crash; escalating response latency | 6 | 5 | 4 | **120** | JVM heap threshold alerts at 80%; Kubernetes rolling pod recycling every 12 hours; stateless offload to PostgreSQL/Redis |
| **FM-011** | Double-Spend Under High Concurrency | Race condition: two withdrawals read same balance | Account balance drops negative; financial discrepancy | 10 | 3 | 2 | **60** | Database schema constraint `CHECK (available_balance >= 0)`; atomic CAS `WHERE version = expected_version`; formal correctness proof |
| **FM-012** | Database Connection Pool Starvation | Unclosed connections, slow long-running queries | New transaction threads block; HTTP 504 timeouts | 8 | 5 | 2 | **80** | PgBouncer transaction-pooling multiplexes 2,000 client conns to 100 backend conns; HikariCP connection timeout bounded to 250ms |
| **FM-013** | Dual-Write Event Loss (DB vs Kafka) | App commits DB update but crashes before Kafka | Downstream services miss event; ledger unsynced | 8 | 4 | 2 | **64** | Transactional Outbox Pattern: outbox event written in same ACID transaction; Debezium CDC streams directly from WAL |
| **FM-014** | Consumer Poison Pill (Deser Error) | Malformed JSON or unhandled schema change | Kafka consumer crashes in infinite retry loop | 6 | 5 | 2 | **60** | Error-handling deserializer catches bad records; routes to `transaction-events-dlq` after 3 retries; commits partition offset |
| **FM-015** | Super-Merchant Hot-Spot Contention | Single merchant receives 40% of flash-sale txns | Single database row lock contention; high abort rate | 7 | 5 | 3 | **105** | Merchant settlement pools: 8 virtual sub-accounts hash-striped across all 4 shards; daily batch consolidation |
| **FM-016** | Cross-Shard Saga Partial Failure | Sender debited on Shard 1, but Shard 3 fails | Funds debited from sender but never credited | 9 | 4 | 2 | **72** | Distributed Saga Orchestrator: automated compensating reversal executes on Shard 1 within 30s; funds credited back |
| **FM-017** | DNS Resolution Outage (Internal) | CoreDNS pod crash or network exhaustion | Services cannot resolve database or Kafka hostnames | 9 | 2 | 3 | **54** | Local NodeLocal DNSCache daemons on every Kubernetes worker node; static IP fallbacks for critical infrastructure |
| **FM-018** | Webhook Receiver Endpoint Down | Partner merchant web server crashes | Notification worker thread saturation | 4 | 7 | 2 | **56** | Circuit breaker `CB-NOTIFY`; failed webhooks enqueued in async retry queue with exponential backoff (up to 24 hrs) |
| **FM-019** | NTP Clock Drift Between Nodes | VM hypervisor clock desynchronization | Distributed locking leases expire prematurely | 8 | 3 | 4 | **96** | Monotonic fencing tokens passed to database; Redis locks use millisecond relative TTL (`PX`); AWS Time Sync Service enabled |
| **FM-020** | API Gateway Rate Limiting Denial | Misconfigured IP-based rate limiting | Legitimate high-volume merchants blocked (429) | 6 | 5 | 3 | **90** | Token-bucket rate limiting implemented per authenticated `account_id` and tier rather than shared client NAT IP |
| **FM-021** | Transaction Reversal Loop | Repeated automated compensation attempts | Infinite reversal events generated | 8 | 2 | 3 | **48** | Deterministic saga state transition rules: `REVERSED` is an immutable terminal state; max 1 compensation attempt permitted |
| **FM-022** | Unbounded Disk Growth on WAL | Read replica lag delays WAL recycling | Primary database disk runs out of space | 9 | 3 | 2 | **54** | `max_slot_wal_keep_size` configured to 50 GB; replication lag alerts fire at 1 GB lag; automated replica detachment |
| **FM-023** | SQL Injection via Dynamic Query | Malicious input payload bypassing ORM | Data exfiltration or table tampering | 10 | 1 | 2 | **20** | 100% parameterized prepared statements via Spring Data JPA and PreparedStatement; strict UUID type checks |
| **FM-024** | Redis Eviction Churn Under Burst | Sudden memory spike evicts active idempotency keys | Duplicate payment processing risk | 9 | 3 | 3 | **81** | Dedicated 96 GB Redis Cluster; `maxmemory-policy = volatile-lru`; memory alerts at 75%; 24-hr strict idempotency TTLs |
| **FM-025** | Ledger Reconciliation Drift | Bug in rounding logic creates balance imbalance | Sum of debits and credits deviates from zero | 10 | 2 | 2 | **40** | All amounts stored as fixed-precision `NUMERIC(18, 4)`; hourly reconciliation job alerts on any discrepancy $> 0.0001\text{ INR}$ |

---

## 3. High-RPN Mitigation Summary

All 25 failure modes have calculated RPN scores between 20 and 120. Through our combination of **sharding, connection pooling, transactional outbox CDC, optimistic concurrency control, Patroni Raft HA, and sliding-window circuit breakers**, zero failure modes exceed the critical 200 RPN threshold. The system possesses robust structural defenses against cascading failures.
