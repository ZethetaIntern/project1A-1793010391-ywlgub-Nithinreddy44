# Scenario Analysis: PayScale Financial Technologies

## 1. Executive Summary & Context

**PayScale Financial Technologies** is a Series-B funded neo-banking institution based in Mumbai, India, serving over 12 million monthly active users (MAUs) and processing approximately 28 million daily transactions across Person-to-Person (P2P) transfers, merchant checkout payments, micro-lending disbursements, and automated settlements. 

The company faces an existential operational milestone: the upcoming **Diwali festive-season sale** launching in 15 days. Marketing promotions, festive cashbacks, flash sales, and partner merchant discount campaigns are projected to surge transaction volume by a factor of 10x. The target user base will expand to 35 million MAUs and daily volumes will exceed 85 million transactions. 

The legacy platform—architected around a monolithic PostgreSQL instance, single-node RabbitMQ message broker, single-node Redis cache, and synchronous database locking—operates at a saturation ceiling of **1,200 Transactions Per Second (TPS)** with an average latency (p50) of 85 ms and a 99th percentile latency (p99) of 450 ms. Under the projected Diwali load of **12,000+ sustained TPS** (and **18,000 TPS peak burst** for 15 minutes), the legacy infrastructure will experience systemic collapse.

This document delivers a root-cause breakdown of recent simulated load-testing bottlenecks and outlines the foundational architectural overhaul required to meet PayScale's performance, correctness, and regulatory mandates.

---

## 2. Quantitative System Baseline vs. Target State

| Parameter | Legacy System State | Diwali Target Specification | Scaling Factor / Delta |
| :--- | :--- | :--- | :--- |
| **Peak Throughput** | 1,200 TPS | **12,000+ TPS** (18,000 burst) | 10x sustained / 15x burst |
| **Latency (p50)** | 85 ms | **≤ 30 ms** | -64.7% reduction |
| **Latency (p99)** | 450 ms | **≤ 100 ms** | -77.7% reduction |
| **Latency (p99.9)** | > 2,000 ms (timeouts) | **≤ 250 ms** | Guaranteed ceiling |
| **Database Engine** | PostgreSQL 15 (Single Primary) | **Distributed Sharded PostgreSQL** (4 active shards + read replicas) | Horizontal scale-out |
| **Connection Pooling** | Direct connections (max 200) | **PgBouncer** connection poolers (2,000+ client conns) | 10x capacity |
| **Message Queue** | RabbitMQ (Single Node) | **Apache Kafka** (3-Broker Cluster, KRaft, 32 partitions) | High-throughput streaming |
| **Cache Tier** | Redis 7.0 (Single Node, 16 GB) | **Redis Cluster** (6 nodes: 3 primaries + 3 replicas, 96 GB) | 6x memory + HA |
| **App Compute** | 8x EC2 `m5.2xlarge` | Auto-scaling Container Cluster (8–64 instances) | Dynamic elastic scale |
| **Monthly Active Users**| 12 Million | **35 Million** | ~3x growth |
| **Daily Transactions** | 28 Million | **85 Million** | ~3x growth |
| **Uptime SLA** | 99.9% (8.76 hrs downtime/yr) | **99.99%** (≤ 52.6 min downtime/yr) | 10x reliability improvement |
| **Failover RTO / RPO** | Manual failover (> 30 min) / RPO > 0 | **RTO ≤ 30s** / **RPO = 0** (Committed data) | Zero financial data loss |
| **Budget Ceiling** | $12,000 / month | **$45,000 / month** | $33,000 scaling budget |

---

## 3. Root Cause Analysis: Simulated Load Test Bottlenecks (Locust 50,000 Users)

During pre-Diwali stress testing using Locust with 50,000 concurrent simulated users over a 2-hour window, the legacy architecture failed at approximately 1,800 TPS. Below is the technical breakdown of the 8 identified bottlenecks:

### BN-001: PostgreSQL Primary Connection Pool Exhaustion (CRITICAL)
* **Observation:** Direct client connections exceeded PostgreSQL `max_connections = 200` at 1,800 TPS. Active backend threads engaged in heavy memory context switching, pushing query queue depths beyond 500 requests and causing connection starvation.
* **Root Cause:** Application services established unpooled, long-lived TCP connections directly to the database. Each backend connection consumed ~10 MB of RAM, leading to memory pressure and query latency degradation.
* **Architectural Remedy:** 
  1. Introduce transactional connection pooling via **PgBouncer** in transaction-pooling mode, multiplexing 2,000+ client connections across a bounded pool of 100 physical database connections per shard.
  2. Horizontally partition account records across 4 PostgreSQL database shards using consistent hashing on `shard_key = hash(account_id) % 4`.

### BN-002: RabbitMQ Message Acknowledgment Backlog & Consumer Lag (CRITICAL)
* **Observation:** Message ack backlog grew monotonically at 2,000 TPS, producing consumer lag exceeding 30 seconds. Erlang node memory alarms triggered queue paging to disk, collapsing broker throughput.
* **Root Cause:** RabbitMQ's per-message state tracking and synchronous broker-side acknowledgment overhead are poorly suited for high-throughput append-only event streams. Complex AMQP routing logic degraded under concurrent consumers.
* **Architectural Remedy:**
  1. Replace RabbitMQ with a **3-broker Apache Kafka Cluster** running KRaft consensus.
  2. Segment transactions into a 32-partition `transaction-events` topic keyed by `account_id`, providing sequential ordering per account with zero broker-side per-message ack overhead.
  3. Implement sequential batch processing with consumer groups matching partition counts.

### BN-003: Application Layer Thread Pool Saturation (HIGH)
* **Observation:** Application worker threads saturated at 2,200 TPS, resulting in a 40% request timeout rate (HTTP 504) and client connection drops.
* **Root Cause:** Synchronous blocking I/O (blocking database queries, downstream REST calls to fraud and notification services) tied up OS threads waiting for network responses.
* **Architectural Remedy:**
  1. Transition to **Java 21 / Spring Boot 3 with Virtual Threads (Project Loom)** and non-blocking asynchronous orchestrators.
  2. Implement bounded thread pools and bulkheads isolating external payment rail integration from core account querying.

### BN-004: Redis Cache Degradation & Eviction Churn (HIGH)
* **Observation:** Cache hit ratio plummeted from 92% to 61% under load; memory evictions (`volatile-lru`) caused thrashing and database query storms.
* **Root Cause:** Single-node Redis instance (16 GB) ran out of physical memory due to unbounded TTLs on idempotency keys and cached account balances without size limits. Single-threaded event loop became CPU-bound during large JSON deserialization.
* **Architectural Remedy:**
  1. Deploy a **6-node Redis Cluster** (3 primaries, 3 replicas) providing 96 GB total RAM distributed across 16,384 hash slots.
  2. Enforce strict 24-hour TTLs on idempotency records and implement a Cache-Aside pattern with jittered expiry (60–120s) for hot account reads.

### BN-005: API Gateway Rate Limiter Misconfiguration (MEDIUM)
* **Observation:** 12% false-positive HTTP 429 (Too Many Requests) rejections for legitimate high-volume merchants during flash sales.
* **Root Cause:** Uniform global IP-based sliding window rate limits treated large corporate aggregators and high-frequency merchants identically to individual P2P retail accounts.
* **Architectural Remedy:**
  1. Implement a **Tiered Token-Bucket Rate Limiter** implemented via Redis Lua scripts:
     * *BASIC Tier (Retail P2P):* 50 TPS burst / 20 TPS sustained.
     * *PREMIUM Tier (High Net Worth):* 200 TPS burst / 100 TPS sustained.
     * *MERCHANT Tier (Partners):* 1,000 TPS burst / 500 TPS sustained.
  2. Key rate limits by authenticated `account_id` and client API key rather than shared NAT gateway IPs.

### BN-006: Database Row-Level Lock Contention & Deadlocks (CRITICAL)
* **Observation:** 15% transaction deadlock rate (`40P01`) on `accounts` table updates during concurrent P2P transfers and flash-sale merchant credits.
* **Root Cause:** P2P transactions locked both sender and receiver rows using `SELECT ... FOR UPDATE`. When Transaction A transferred from Account 1 to Account 2 while Transaction B transferred from Account 2 to Account 1 simultaneously, interleaved locking caused classic circular deadlocks.
* **Architectural Remedy:**
  1. Eliminate pessimistic `SELECT ... FOR UPDATE` row locks in favor of **Optimistic Concurrency Control (OCC)** using version counters (`version BIGINT`).
  2. Balance updates use atomic Compare-And-Swap (CAS):
     ```sql
     UPDATE accounts 
     SET available_balance = available_balance - :amount, 
         version = version + 1, 
         updated_at = NOW() 
     WHERE account_id = :accountId AND version = :expectedVersion AND available_balance >= :amount;
     ```
  3. When lock collisions occur, execute bounded exponential backoff retries (maximum 3 retries within 15 ms).
  4. Enforce strict alphabetical account ID ordering during multi-account batch settlements.

### BN-007: Network I/O Cross-AZ Latency Spikes (MEDIUM)
* **Observation:** Inter-Availability Zone network latency spiked to 18 ms average during failover events, causing end-to-end transaction latency to violate the 100 ms p99 SLA.
* **Root Cause:** Uncoordinated microservice placement resulted in cross-AZ network hops between the API Gateway in AZ-1, Payment Service in AZ-2, and Database Primary in AZ-3.
* **Architectural Remedy:**
  1. Configure **AZ-Affinity routing** with AWS Application Load Balancers and Kubernetes topology-aware routing, keeping request-reply paths within the same AZ during normal operations.
  2. Leverage AWS Direct Connect or dedicated VPC peering links with TCP keep-alive and connection pooling across service boundaries.

### BN-008: Log Aggregation Ingestion Lag & Observability Loss (LOW)
* **Observation:** Elasticsearch ingestion lag exceeded 5 minutes during peak load, causing complete loss of real-time monitoring and alert blindness.
* **Root Cause:** Synchronous JSON logging over TCP directly to Logstash saturated network buffers.
* **Architectural Remedy:**
  1. Decouple logging using an asynchronous, zero-allocation logging pipeline (Logback async appender with RingBuffer).
  2. Stream high-cardinality audit logs to a dedicated Kafka topic `audit-events` consumed by ClickHouse/Loki, while maintaining sub-second metric scraping via Prometheus and OpenTelemetry agents.

---

## 4. Financial Correctness & Regulatory Mandates

PayScale operates under strict Reserve Bank of India (RBI) regulatory directives for digital payment systems:

1. **ACID & Double-Entry Bookkeeping:** Every monetary transfer must generate equal and offsetting debit and credit `ledger_entries`. The sum of all ledger debits and credits across the entire platform must strictly equal zero ($\sum \text{debits} - \sum \text{credits} = 0$).
2. **Dual-Balance Architecture:** Accounts maintain two independent balances:
   * `available_balance`: Checked for real-time authorization; reflects immediate commitments.
   * `ledger_balance`: Settled accounting balance updated upon confirmed transaction completion.
   * Reconciliation lag between available and ledger balances must not exceed 500 ms.
3. **No Unfunded Overdrafts:** Zero negative balances are permitted unless an account has an explicit credit facility tier.
4. **Idempotency Guarantee:** All payment submission APIs require a client-generated UUIDv7 `Idempotency-Key` with a mandatory 24-hour retention window in Redis and PostgreSQL. Repeated requests with identical payloads return cached results with zero duplicate financial effects. Conflicting payloads with an existing key return HTTP 409 Conflict.
5. **State Transition Integrity:** All transactions transition through explicit, auditable states:
   $$\text{INITIATED} \longrightarrow \text{PROCESSING} \longrightarrow \begin{cases} \text{COMPLETED} \\ \text{FAILED} \longrightarrow \text{REVERSED} \end{cases}$$
6. **Compensating Reversals:** Transactions that fail midway through execution (e.g., receiver credit fails after sender debit) must execute automated saga compensation within **30 seconds**.
7. **Data Residency Compliance:** All transaction records, audit logs, and customer KYC artifacts must reside exclusively on servers physically located within the Republic of India (AWS `ap-south-1` Mumbai region).

---

## 5. 15-Day Architecture Sprint Roadmap

| Phase | Days | Focus Area | Key Deliverables |
| :--- | :--- | :--- | :--- |
| **Phase 1** | Day 1–2 | Problem Analysis & Tech Selection | Scenario analysis, Technology evaluation matrices, ADR-001 (Kafka) & ADR-002 (DB) |
| **Phase 2** | Day 3–4 | High-Level Design & Data Flows | 13-component HLD, PlantUML sequence diagrams (P2P, Settlement, Failover) |
| **Phase 3** | Day 5–6 | Data Tier & Sharding Architecture | PostgreSQL DDL migrations, DBML ERD, Sharding Strategy, ADR-003 (Sharding) |
| **Phase 4** | Day 7–8 | Event Streaming & Concurrency | Kafka topology, OCC balance update engine, Saga orchestrator, ADR-004 & ADR-005 |
| **Phase 5** | Day 9–10 | Fault Tolerance & API Design | Circuit breakers, Bulkheads, OpenAPI 3.0 specification, Idempotency handler |
| **Phase 6** | Day 11–12 | Performance, Load Testing & Budget | 8 load test scenarios (Locust/k6), Performance budget, Capacity plan ($40,850/mo) |
| **Phase 7** | Day 13 | Risk Analysis & Chaos Engineering | 20+ failure mode FMEA with RPN analysis, 5 chaos engineering experiment designs |
| **Phase 8** | Day 14 | Integration, Polish & Self-Assessment | Full test validation, SELF-ASSESSMENT.md, REFLECTION.md |
| **Phase 9** | Day 15 | ARB Defense & Project Handoff | Architecture Review Board presentation deck, Docker Compose verification |

---

## 6. Conclusion

By addressing the 8 core bottlenecks through database sharding, connection pooling, Kafka event streaming, Redis clustering, and Optimistic Concurrency Control, PayScale Financial Technologies can comfortably achieve 12,000+ sustained TPS and 18,000 burst TPS while maintaining strict double-entry financial correctness and a total monthly infrastructure spend of $40,850—well beneath the $45,000 budget ceiling.
