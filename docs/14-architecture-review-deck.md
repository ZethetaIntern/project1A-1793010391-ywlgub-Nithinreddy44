# Architecture Review Board (ARB) Presentation & Defense Deck: PayScale HTTPE

## 1. Executive Presentation Agenda (15-Minute Pitch)

```
+--------------------------------------------------------------------------------------------------+
|                     ARCHITECTURE REVIEW BOARD DEFENSE: 15-MINUTE AGENDA                          |
|  1. Problem Statement & Legacy Bottleneck Breakdown (2 min)                                      |
|  2. Target Architecture: The 13 Core Components (3 min)                                          |
|  3. Financial Correctness: OCC, Double-Entry & Formal Invariants (3 min)                         |
|  4. Database Sharding & Kafka Streaming Topology (3 min)                                         |
|  5. Fault Tolerance, 30s RTO & Capacity Sizing ($40,850/mo <= $45k ceiling) (2 min)              |
|  6. Summary, Demonstration & Q&A Transition (2 min)                                              |
+--------------------------------------------------------------------------------------------------+
```

---

## 2. Slide Deck Outline & Script

### Slide 1: Title & Confidentiality Notice
* **Header:** PayScale High-Throughput Transaction Processing Engine (HTTPE)
* **Subtitle:** Scaling Core Banking from 1,200 to 12,000+ Sustained TPS Ahead of the Diwali Festive Surge
* **Presenter:** Lead Distributed Systems & Reliability Architect
* **Notice:** *Strictly Private & Confidential - Zetheta Algorithms Assessment Protocol*

### Slide 2: The Diwali Scaling Challenge
* **The Crisis:** PayScale serves 12M MAUs and 28M daily transactions. Marketing's Diwali campaign launches in 15 days, projecting 10x traffic surge (12,000+ sustained TPS, 18,000 burst TPS).
* **The Legacy Failure:** In Locust testing with 50,000 concurrent users, the legacy single-node PostgreSQL and RabbitMQ architecture collapsed at 1,800 TPS:
  * Connection pool exhaustion (`max_connections = 200`, query queue > 500)
  * RabbitMQ acknowledgment backlog causing > 30s consumer lag
  * Database row lock deadlocks (15% failure rate on `accounts`)
  * Application thread pool saturation (40% timeout rate)
* **The Mandate:** Complete architectural overhaul achieving $\le 30\text{ ms } p50$, $\le 100\text{ ms } p99$, $99.99\%$ availability, $\text{RTO} \le 30\text{s}$, $\text{RPO} = 0$, within a $\$45,000\text{/month}$ budget.

### Slide 3: Target Architecture Overview (The 13 Subsystems)
* **Edge Ingestion:** Envoy / Spring Cloud Gateway with distributed Redis token-bucket rate limiting (BASIC: 50, PREMIUM: 200, MERCHANT: 1,000 TPS) and AWS NLB L7 load balancing.
* **Core Application Engine:** Java 21 / Spring Boot 3 running Virtual Threads (Project Loom), eliminating thread starvation.
* **Event Streaming:** 3-broker Apache Kafka cluster (KRaft consensus, 32 partitions on `transaction-events`).
* **Persistence Tier:** 4 PostgreSQL 15 database shards fronted by PgBouncer transaction-pooling (2,000+ client conns -> 100 backend conns).
* **In-Memory Tier:** 6-node Redis Cluster (96 GB total) managing 24-hr idempotency keys and hot account caches.
* **Resilience:** Patroni Raft HA (RTO < 30s), Circuit breakers (`CB-FRAUD`, `CB-NOTIFY`), and Transactional Outbox CDC.

### Slide 4: Financial Correctness & Formal Verification
* **Double-Entry Bookkeeping:** Every transfer creates equal and offsetting DEBIT and CREDIT records; global invariant $\sum \text{Debits} - \sum \text{Credits} = 0$.
* **Dual-Balance Model:** Segregated `available_balance` (immediate reservation) and `ledger_balance` (settled book balance).
* **Optimistic Concurrency Control (OCC):** Replaces pessimistic row locks with version vectors and atomic Compare-And-Swap (CAS):
  ```sql
  UPDATE accounts 
  SET available_balance = available_balance - :amount, version = version + 1
  WHERE account_id = :id AND version = :v AND available_balance >= :amount;
  ```
* **Formal Correctness:** Mathematical proof confirms double-spend and write-skew anomalies are structurally impossible.

### Slide 5: Sharding Strategy & Hot-Partition Mitigation
* **Shard Key:** Algorithmic hash partitioning on `abs(MurmurHash3(account_id)) % 4` achieves mathematically uniform distribution across 35 million accounts with $< 0.05\%$ variance.
* **Super-Merchant Solution:** High-volume merchants (> 200 TPS) receive 8 virtual sub-accounts hash-striped across all 4 shards, eliminating single-row contention during flash sales.
* **Cross-Shard Transactions:** Coordinated via a Distributed Saga Orchestrator with automated compensating reversals within 30 seconds rather than blocking 2PC.

### Slide 6: Capacity Planning & Budget Compliance
* **Instance Count:** 61 total managed instances across compute, caching, streaming, and database tiers.
* **Resource Totals:** 354 vCPUs, 1,348 GB RAM, 10.5 TB storage.
* **Total Monthly AWS Cost:** **$40,850 / month**, strictly under the $45,000 budget ceiling.
* **Optimization Pathway:** 3-year Compute Savings Plans and Reserved Instances reduce ongoing spend to **$28,450 / month**.

---

## 3. Defense Script: Sample Architecture Review Board (ARB) Q&A

### ARB Question 1:
> *"Your sharding strategy uses `account_id` as the shard key. What happens when a single merchant processes 40% of all transactions during a Diwali flash sale? How do you handle this hot-partition scenario?"*

**Architect Defense:**
> "Under a naive single-account model, a merchant receiving 40% of 12,000 TPS (4,800 writes/second) would cause catastrophic OCC version aborts on a single row. We mitigate this through **Virtual Merchant Settlement Pools**. Any merchant categorized under the `MERCHANT` tier with high burst traffic is allocated 8 virtual sub-accounts (`MERCHANT_SUB_0` through `_7`) hash-striped across all 4 database shards. Incoming customer checkouts randomly select one of these sub-accounts. This distributes the 4,800 TPS across 8 independent rows and 4 physical database shards, reducing write frequency to a manageable 600 TPS per row. During off-peak batch settlement, our Reconciliation Service aggregates these virtual accounts into the primary merchant account in parallel, maintaining 100% balance accuracy with zero transaction blocking."

### ARB Question 2:
> *"Walk me through exactly what happens when a P2P payment is initiated but the sender's database shard goes down mid-transaction. Which component detects the failure, what compensation logic runs, and what does the user see?"*

**Architect Defense:**
> "Let us trace the exact timeline:
> 1. *Detection:* When the primary node on Shard 1 fails, PgBouncer immediately detects TCP connection termination and pauses incoming client queries. Concurrently, Patroni's DCS watchdog detects the missing heartbeat within 5 seconds.
> 2. *High Availability Promotion:* Patroni verifies the synchronous physical standby has the latest committed WAL LSN and issues `pg_promote()`. Total standby promotion time is 12–15 seconds. PgBouncer re-routes traffic to the new primary.
> 3. *In-Flight Transaction Handling:* If the sender's debit had already committed to the Write-Ahead Log before the crash, synchronous replication guarantees that committed transaction exists on the new primary. The Transaction Orchestrator resumes the saga and credits the receiver on Shard 3.
> 4. *Compensating Reversal:* If the sender's debit was committed but the receiver's shard subsequently rejects the credit (e.g. frozen account), the Orchestrator initiates an automatic compensation saga on Shard 1 within 30 seconds, crediting back the sender's `available_balance`, writing a `REVERSAL` ledger entry, and emitting a `TRANSACTION_REVERSED` Kafka event.
> 5. *User Visibility:* The client's HTTP request receives a transparent response in under 18 seconds (well beneath our 30-second RTO), or receives a clear push notification explaining that the transfer was reversed with zero funds deducted."

### ARB Question 3:
> *"You chose Apache Kafka over RabbitMQ. If the team has zero Kafka experience and the Diwali deadline is 15 days away, would you change your decision? Why or why not?"*

**Architect Defense:**
> "I would firmly maintain our decision to deploy Apache Kafka, but with an operational strategy that neutralizes team learning-curve risk:
> 1. *Technical Impossibility of RabbitMQ:* In our Locust stress test, RabbitMQ failed at 2,000 TPS due to per-message queue index overhead and Erlang memory alarms. At 12,000 sustained and 18,000 burst TPS, RabbitMQ will fail catastrophically; sticking with RabbitMQ guarantees an operational blackout on Diwali day.
> 2. *Operational De-risking:* Rather than building a raw, self-managed Apache Kafka cluster from scratch, we utilize **Amazon Managed Streaming for Apache Kafka (Amazon MSK)** in the Mumbai region with KRaft consensus. MSK automates broker provisioning, storage auto-expansion, OS patching, and multi-AZ replication.
> 3. *Abstraction in Application Code:* For our Spring Boot engineering team, interacting with Kafka is completely abstracted through standard `Spring-Kafka` templates and `@KafkaListener` annotations. From a developer perspective, publishing an event is identical to sending a message.
> 4. *Conclusion:* Changing to RabbitMQ would sacrifice system survivability. Deploying managed Kafka ensures we meet the Diwali traffic surge with full confidence."

### ARB Question 4:
> *"Your FMEA shows Redis cluster failure as RPN 48. I think that's too low. Convince me or revise it."*

**Architect Defense:**
> "Our RPN calculation of 48 is based on Severity = 6, Occurrence = 4, Detection = 2:
> 1. *Why Detection is Low (D = 2):* Redis Cluster master failures are detected in < 1 second by Redis Sentinel heartbeats and cluster gossip protocol.
> 2. *Why Severity is Bounded (S = 6):* Redis in our architecture is an acceleration cache, not the persistent system of record. Every persistent account balance and transaction record lives in PostgreSQL ACID storage. If a Redis node fails:
>    * Automated replica promotion elects a standby master within 5 seconds.
>    * If the entire cluster were temporarily inaccessible, our circuit breaker `CB-CACHE` trips after 3 failures, bypassing Redis and routing balance reads directly to PostgreSQL read replicas.
>    * Idempotency checking falls back to PostgreSQL's `uq_transactions_idempotency` unique constraint.
> 3. *Conclusion:* Because a total cache outage degrades read latency from 1 ms to 8 ms without causing data corruption or double-spending, a Severity of 6 and overall RPN of 48 is mathematically and architecturally defensible."

---

## 4. Conclusion & Handover Protocol

PayScale's High-Throughput Transaction Processing Engine establishes a production-grade, highly available financial backbone capable of scaling to 12,000+ sustained TPS and 18,000 burst TPS while maintaining full compliance with RBI regulations and remaining within our $45,000/month budget ceiling.
