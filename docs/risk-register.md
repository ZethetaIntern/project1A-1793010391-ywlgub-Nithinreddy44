# Technical & Operational Risk Register: PayScale HTTPE

## 1. Risk Management Framework

In high-throughput financial infrastructure, risk management systematically identifies, scores, and mitigates architectural, operational, and regulatory threats.

Risks are evaluated using a standard 5x5 Probability and Impact matrix:
* **Severity/Impact (1–5):** 1 = Negligible, 2 = Minor, 3 = Moderate, 4 = Major, 5 = Catastrophic (Financial loss / Regulatory sanction).
* **Likelihood/Probability (1–5):** 1 = Rare, 2 = Unlikely, 3 = Moderate, 4 = Likely, 5 = Almost Certain.
* **Risk Score:** $\text{Likelihood} \times \text{Impact}$ (Scores $\ge 15$ represent High Priority risks requiring mandatory architectural controls).

---

## 2. Risk Register Matrix

| Risk ID | Category | Risk Description | Likelihood (1-5) | Impact (1-5) | Score (1-25) | Mitigation Strategy & Architectural Control | Owner |
| :--- | :--- | :--- | :---: | :---: | :---: | :--- | :--- |
| **RSK-01** | Correctness | **Double-Spend via Concurrent Withdrawals:** Race condition allowing simultaneous transactions to spend the same balance | 3 | 5 | **15** | Optimistic Concurrency Control (OCC) with atomic Compare-And-Swap (CAS) version checks + DB constraint `available_balance >= 0` | Core Banking Lead |
| **RSK-02** | Performance | **PostgreSQL Connection Starvation:** 50,000 concurrent users exhausting database connection pools | 4 | 4 | **16** | Transaction-pooling connection poolers (PgBouncer) multiplexing 2,000 client conns to 100 backend conns per shard | Infrastructure Lead |
| **RSK-03** | Reliability | **Dual-Write Event Divergence:** Database commits balance update but app crashes before publishing Kafka event | 3 | 5 | **15** | Transactional Outbox Pattern: outbox event committed inside the same ACID transaction; Debezium CDC captures directly from WAL | Distributed Systems Lead |
| **RSK-04** | Performance | **Super-Merchant Hot-Spot Contention:** Flash sale directs 40% of transactions to single merchant account | 4 | 4 | **16** | Merchant settlement pools: 8 virtual sub-accounts hash-striped across all 4 shards; daily batch consolidation | Data Architect |
| **RSK-05** | Performance | **Fraud Service Latency Cascades:** ML fraud inference delays block application threads and breach 100ms p99 SLA | 3 | 4 | **12** | Strict 15ms circuit breaker (`CB-FRAUD`); fallback approves txns < ₹5,000 with `MANUAL_REVIEW_FLAGGED`; OTP for > ₹5,000 | FinTech Security Lead |
| **RSK-06** | Financial | **Infrastructure Budget Breach:** Cloud resources exceed $45,000/month budget ceiling under 18,000 TPS | 3 | 4 | **12** | Capacity model sized at $40,850/mo; 3-year Compute Savings Plans and Reserved Instances reduce spend to $28,450/mo | DevOps Lead |
| **RSK-07** | Regulatory | **RBI Data Residency Violation:** Cloud replica or audit log stored outside Indian sovereign borders | 1 | 5 | **5** | Enforce AWS Mumbai (`ap-south-1`) region lock; VPC network egress firewalls prevent foreign data replication | Compliance Officer |
| **RSK-08** | Operations | **Kafka Consumer Poison Pill:** Malformed event stalls consumer group in infinite retry loop | 3 | 4 | **12** | Dead Letter Queue (`transaction-events-dlq`): after 3 retries, route failed event to DLQ with error stack and commit offset | Platform Engineer |
| **RSK-09** | Availability | **Primary Database Node Failure:** Hardware or disk crash halts all write traffic on affected shard | 2 | 5 | **10** | Patroni Raft automated failover promotes synchronous standby within 30s with zero committed data loss (RPO = 0) | SRE Lead |
| **RSK-10** | Security | **Idempotency Key Collision / Tampering:** Attacker reuses existing idempotency key with different payload | 2 | 4 | **8** | Canonical SHA-256 payload hashing; mismatched payload returns HTTP 409 Conflict with fraud audit flag | Security Engineer |
