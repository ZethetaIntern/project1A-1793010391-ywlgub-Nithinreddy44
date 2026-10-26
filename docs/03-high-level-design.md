# High-Level Architecture Design (HLD): PayScale HTTPE

## 1. System Context & Overview

The **High-Throughput Transaction Processing Engine (HTTPE)** for **PayScale Financial Technologies** is a distributed, event-driven banking platform designed to process 12,000+ sustained Transactions Per Second (TPS) and 18,000 burst TPS during peak festive-season operations.

The system encompasses **13 core architecture components** collaborating across edge ingestion, transaction orchestration, double-entry financial ledger accounting, distributed caching, event streaming, and operational observability.

```
+----------------------------------------------------------------------------------------------------+
|                                     CLIENT INGESTION TIER                                          |
|   Mobile Apps (iOS/Android)   |   Merchant POS Terminals   |   Third-Party API Integrations        |
+----------------------------------------------------------------------------------------------------+
                                                  | (HTTPS / TLS 1.3 / WSS)
                                                  v
+----------------------------------------------------------------------------------------------------+
|                                      1. API GATEWAY LAYER                                          |
|   - SSL Termination & mTLS    - Token-Bucket Rate Limiter (Redis Lua)    - JWT / OAuth2 Auth       |
|   - Request Validation (UUID) - Idempotency Key Validation               - Canary & Dynamic Routing|
+----------------------------------------------------------------------------------------------------+
                                                  |
                                                  v
+----------------------------------------------------------------------------------------------------+
|                                     2. LOAD BALANCING LAYER                                        |
|   - AWS Network / Application Load Balancers (NLB/ALB)                   - AZ-Affinity Routing     |
|   - L7 Health Checks & Connection Draining (90s graceful shutdown)       - TCP Keep-Alive Multiplex|
+----------------------------------------------------------------------------------------------------+
                                                  |
         +----------------------------------------+----------------------------------------+
         |                                                                                 |
         v                                                                                 v
+-----------------------------------+                             +-----------------------------------+
|    3. TRANSACTION ORCHESTRATOR    |                             |        5. ACCOUNT SERVICE         |
| - Distributed Saga State Machine  | <-------------------------> | - Account CRUD & Tier Mgmt        |
| - Explicit State Management       |                             | - Available vs Ledger Balances    |
| - Automated Compensating Reversals|                             | - OCC Version Vector Engine (CAS) |
| - Step Timeout & Retry Logic      |                             | - Account Freezing & Policy Checks|
+-----------------------------------+                             +-----------------------------------+
         |                                                                                 ^
         | (Local In-Memory / gRPC)                                                        |
         v                                                                                 |
+-----------------------------------+                                                      |
|    4. PAYMENT PROCESSING SVC      |                                                      |
| - Balance Pre-Check Validation    | -----------------------------------------------------+
| - Debit / Credit Ledger Execution |
| - Transaction Hash Generation     |
| - Outbox Event Generation         |
+-----------------------------------+
         |
         +---------------------------------------------------------+
         |                                                         |
         v                                                         v
+-----------------------------------+             +-----------------------------------+
|     7. FRAUD DETECTION SVC        |             |        8. RECONCILIATION SVC      |
| - 15ms Strict Latency SLA         |             | - Double-Entry Ledger Verification|
| - In-Memory Feature Store (Redis) |             | - Zero-Sum Platform Audit Check   |
| - Velocity & Geolocation Rules    |             | - Cross-Shard Imbalance Detection |
| - Circuit Breaker (CB-FRAUD)      |             | - Automated Discrepancy Alerting  |
+-----------------------------------+             +-----------------------------------+
         |
         v
+----------------------------------------------------------------------------------------------------+
|                             10. MESSAGE QUEUE / EVENT BUS (APACHE KAFKA)                           |
|   - 3-Broker Cluster (KRaft Consensus)     - 32 Partitions on `transaction-events`                  |
|   - Key: `account_id` (Per-Account FIFO)   - Replication Factor = 3 (min.insync.replicas = 2)      |
|   - Transactional Outbox CDC Ingestion     - Dead Letter Queue (DLQ) with Exponential Backoff      |
+----------------------------------------------------------------------------------------------------+
         |                                                         |
         +----------------------------+                            +----------------------------+
         v                            v                                                         v
+-----------------------------------+ +-----------------------------------+ +-----------------------------------+
|      6. NOTIFICATION SERVICE      | |    9. AUDIT & COMPLIANCE SVC      | |       12. CACHE LAYER (REDIS)     |
| - WebSocket Gateway (50K conns)   | | - Immutable Append-Only Audit Log | | - 6-Node Redis Cluster (96 GB)    |
| - Webhook Delivery with Signatures| | - PII Masking & Hash Anonymization| | - Idempotency Keys (24hr TTL)     |
| - Push / SMS Notification Queue   | | - RBI Regulatory Reporting        | | - Rate Limit Token Buckets        |
| - Circuit Breaker (CB-NOTIFY)     | | - Cryptographic Signature Chain   | | - Hot Account Read-Through Cache  |
+-----------------------------------+ +-----------------------------------+ +-----------------------------------+
                                                                                                |
+-----------------------------------------------------------------------------------------------+----+
|                                    11. DATABASE LAYER (SHARDED POSTGRESQL)                         |
|   - 4 Independent Database Shards (Shards 0..3) with Consistent Hashing: `hash(account_id) % 4`    |
|   - Dedicated PgBouncer Connection Poolers (2,000+ client conns -> 100 backend conns/shard)        |
|   - Primary + Synchronous Standby (Patroni/Raft RTO < 30s) + Read Replica per Shard                |
|   - Time-Based Declarative Partitioning on `transactions` and `ledger_entries` (Monthly Ranges)    |
+----------------------------------------------------------------------------------------------------+
                                                  |
+----------------------------------------------------------------------------------------------------+
|                                   13. OBSERVABILITY & TELEMETRY STACK                              |
|   - Prometheus Metrics Scraper     - Grafana Dashboards (5 Golden Views)   - OpenTelemetry Tracing |
|   - Centralized Async Log Pipeline - Alertmanager P1-P4 Tiered Escalations - Health/Readiness Probes|
+----------------------------------------------------------------------------------------------------+
```

---

## 2. Deep Dive: The 13 Architecture Components

### Component 1: API Gateway Layer
* **Responsibility:** Ingress entry point for all client and merchant requests. Performs hardware-offloaded TLS 1.3 termination, OAuth2/JWT cryptographic validation, client payload validation, distributed rate limiting, and request routing.
* **Interfaces:** 
  * Inbound: HTTPS (Port 443) from external clients.
  * Outbound: Internal HTTP/2 or gRPC to Application Services.
  * Integration: Redis Cluster for rate-limit token bucket counters.
* **Technology Choice & Justification:** **Envoy Proxy / Spring Cloud Gateway**. Envoy provides native C++ performance with sub-millisecond connection handling, native HTTP/2 multiplexing, and dynamic route discovery. Spring Cloud Gateway offers seamless integration with the JVM security filter chain.
* **Scaling Strategy:** Horizontally auto-scaled (4 to 16 instances) based on CPU utilization (> 70%) and active connection thresholds.
* **Failure Handling:** If downstream services degrade, the Gateway enforces circuit breaking, returning structured HTTP 503 Service Unavailable responses with `Retry-After` headers, preventing upstream traffic from overwhelming struggling services.

### Component 2: Load Balancer Layer
* **Responsibility:** Layer 7 traffic distribution across container instances. Enforces health checking, connection draining, session distribution, and cross-AZ affinity.
* **Interfaces:** Sits between the Internet Gateway and API Gateway instances, and between the API Gateway and internal service clusters.
* **Technology Choice & Justification:** **AWS Application Load Balancer (ALB) and Network Load Balancer (NLB)**. NLB provides ultra-low latency Layer 4 routing capable of handling millions of concurrent connections, offloading DDoS protection to AWS Shield. ALB provides path-based L7 routing.
* **Scaling Strategy:** Elastic managed scaling with automated target group health registration.
* **Failure Handling:** Performs active health checks (`/actuator/health/readiness`) every 5 seconds. Unhealthy instances are drained within 10 seconds and removed from the active routing table without dropping active TCP streams.

### Component 3: Transaction Orchestrator
* **Responsibility:** Manages the distributed lifecycle of financial transactions through a deterministic **Saga State Machine**:
  $$\text{INITIATED} \longrightarrow \text{PROCESSING} \longrightarrow \begin{cases} \text{COMPLETED} \\ \text{FAILED} \longrightarrow \text{REVERSED} \end{cases}$$
  Maintains step state, dispatches balance reservation commands, coordinates fraud checks, and executes automatic compensating reversals within the 30-second RTO window upon partial failures.
* **Interfaces:** Communicates synchronously with Account and Payment services for immediate balance actions; publishes events to Kafka; queries Redis for idempotency coordination.
* **Technology Choice & Justification:** **Custom High-Performance State Machine Engine in Java 21** backed by PostgreSQL event persistence. Bypasses external orchestrator latency overhead while maintaining strict state transition auditability.
* **Scaling Strategy:** Horizontally scaled (8 to 32 instances). Partition affinity is maintained via Kafka message key routing on `transaction_id`.
* **Failure Handling:** If an orchestrator instance crashes, persistent saga state in `transaction_events` allows any standby orchestrator node to resume in-flight sagas from the last committed checkpoint.

### Component 4: Payment Processing Service
* **Responsibility:** Executes core financial transfer logic. Validates sender balance sufficiency, applies business fee logic, generates cryptographic transaction hashes, inserts atomic debit and credit entries into `ledger_entries`, and writes events to the transactional `outbox_events` table within a single ACID transaction.
* **Interfaces:** Invoked by Transaction Orchestrator; interacts directly with the local PostgreSQL shard.
* **Technology Choice & Justification:** **Java 21 / Spring Boot 3 with Virtual Threads**. Delivers high concurrent I/O throughput with minimal memory footprint per request.
* **Scaling Strategy:** 12 to 48 container instances distributed evenly across Availability Zones.
* **Failure Handling:** Executes within an isolated database transaction. Any runtime error, OCC collision, or constraint violation triggers an immediate `ROLLBACK`, leaving ledger balances completely untouched.

### Component 5: Account Service
* **Responsibility:** Manages customer account profiles, account status (ACTIVE, FROZEN, CLOSED), tier thresholds (BASIC: 50 TPS, PREMIUM: 200 TPS, MERCHANT: 1,000 TPS), and balance modifications. Enforces Optimistic Concurrency Control (OCC) using atomic Compare-And-Swap (CAS) version checks.
* **Interfaces:** Exposes REST and gRPC endpoints to Payment Processing and Orchestrator; queries local PostgreSQL shard and Redis cache.
* **Technology Choice & Justification:** **Java 21 / Spring Data JPA & Sharded JDBC**. Ensures strict type safety, zero floating-point arithmetic errors (using `BigDecimal` / `NUMERIC(18, 4)`), and direct SQL CAS execution.
* **Scaling Strategy:** 6 to 24 instances with localized shard affinity.
* **Failure Handling:** Rejects stale writes with OCC `OptimisticLockException` and executes up to 3 automated retries with randomized exponential backoff (2–10 ms).

### Component 6: Notification Service
* **Responsibility:** Dispatches real-time transaction receipts, balance updates, and merchant settlement alerts via WebSockets, webhooks, SMS, and push notifications. Supports 50,000 concurrent WebSocket connections.
* **Interfaces:** Consumes `notification-events` Kafka topic; manages outbound client WebSocket channels and webhook HTTP callbacks.
* **Technology Choice & Justification:** **Netty / Spring WebFlux with Redis Pub/Sub**. Non-blocking event-loop architecture handles tens of thousands of idle or streaming WebSocket connections with negligible memory overhead (~15 KB per connection).
* **Scaling Strategy:** 3 to 12 instances sized specifically for TCP socket descriptor capacity.
* **Failure Handling:** Backed by Circuit Breaker `CB-NOTIFY`. Failed webhook deliveries are enqueued in a retry queue with exponential backoff (up to 24 hours) without blocking core transaction processing.

### Component 7: Fraud Detection Service
* **Responsibility:** Real-time pre-authorization fraud evaluation. Computes user velocity checks (e.g., number of transactions in the last 60 seconds), geofencing anomalies, and blacklisted merchant checks within a strict **15 ms latency budget**.
* **Interfaces:** Synchronous HTTP/gRPC call from Transaction Orchestrator; sub-millisecond read access to Redis Feature Store.
* **Technology Choice & Justification:** **Lightweight In-Memory Rule Engine in Java + Redis Feature Store + ONNX Runtime**. Executes deterministic rule evaluation in < 3 ms and vectorized ML scoring in < 8 ms.
* **Scaling Strategy:** 4 to 16 compute-optimized instances (`c6i.2xlarge`).
* **Failure Handling:** Protected by Circuit Breaker `CB-FRAUD` (trips after 3 failures in 10s with 15s reset). When open, transactions under ₹5,000 are approved with a `MANUAL_REVIEW_FLAGGED` audit tag, while high-value transfers require step-up OTP authentication.

### Component 8: Reconciliation Service
* **Responsibility:** Offline and near-real-time auditing of financial ledger balance integrity. Verifies that the platform double-entry equation strictly balances ($\sum \text{Debits} - \sum \text{Credits} = 0$) and detects any drift between `available_balance` and `ledger_balance`.
* **Interfaces:** Consumes Kafka `transaction-events`; executes read-only SQL queries against PostgreSQL read replicas.
* **Technology Choice & Justification:** **Spring Batch & ClickHouse / DuckDB Analytics**. High-speed vector aggregation compares millions of journal entries without imposing query load on primary transaction shards.
* **Scaling Strategy:** Scheduled cron jobs and streaming consumers running on dedicated worker nodes.
* **Failure Handling:** Generates immediate P1 alerts to the engineering and finance on-call channels upon detecting any ledger discrepancy $> 0.0001\text{ INR}$, halting affected accounts automatically.

### Component 9: Audit & Compliance Service
* **Responsibility:** Captures an immutable, tamper-evident audit record of every transaction state transition, user KYC update, and administrative intervention, complying with RBI digital payment directives. Enforces PII masking (hashing PAN/Aadhaar and masking bank account numbers).
* **Interfaces:** Consumes Kafka `audit-events` topic; writes to write-once-read-many (WORM) append-only storage.
* **Technology Choice & Justification:** **PostgreSQL Append-Only Tables + Amazon S3 Glacier Vault Lock**. Enforces cryptographic SHA-256 chain of custody on all audit rows.
* **Scaling Strategy:** 3 worker instances with decoupled async batching.
* **Failure Handling:** If the audit service or storage is temporarily unreachable, events remain durable in Kafka (7-day retention) until successfully flushed.

### Component 10: Message Queue / Event Bus (Apache Kafka)
* **Responsibility:** Central asynchronous distributed backbone. Decouples transaction ingestion from downstream notification, fraud analytics, and settlement pipelines.
* **Interfaces:** Producers: Transaction Outbox CDC / Orchestrator; Consumers: Notification, Audit, Reconciliation, Settlement.
* **Technology Choice & Justification:** **Apache Kafka 3.6+ with KRaft consensus**. 3 brokers, 32 partitions on `transaction-events`, `replication.factor = 3`, `min.insync.replicas = 2`.
* **Scaling Strategy:** Cluster capacity scales to 100,000+ msg/sec by adding partitions and brokers.
* **Failure Handling:** Tolerates single-broker crash without failover delay or data loss. Unprocessable messages transition to `transaction-events-dlq`.

### Component 11: Database Layer (Distributed Sharded PostgreSQL)
* **Responsibility:** Persistent system of record guaranteeing ACID properties for all accounts, transactions, and double-entry ledger journals.
* **Interfaces:** JDBC connection pools via PgBouncer.
* **Technology Choice & Justification:** **PostgreSQL 15 across 4 active shards**, managed by Patroni for high availability with Raft-backed automated failover (RTO < 30s). PgBouncer in transaction-pooling mode supports 2,000+ client connections.
* **Scaling Strategy:** Shard-out horizontally by doubling shard counts (4 to 8 shards) via consistent hashing.
* **Failure Handling:** Automatic primary failover to synchronous standby within 15–25 seconds with zero committed data loss (RPO = 0).

### Component 12: Cache Layer (Redis Cluster)
* **Responsibility:** Ultra-low-latency in-memory data store for 24-hour idempotency keys, rate-limiting token buckets, hot account balance read caching, and distributed fencing token locks.
* **Interfaces:** Redis client protocol (Jedis / Lettuce) over TCP.
* **Technology Choice & Justification:** **Redis Cluster 7.2 (6 nodes: 3 primaries + 3 replicas)**, providing 96 GB total memory distributed across 16,384 hash slots.
* **Scaling Strategy:** Online hash slot migration allows expanding to 9 or 12 nodes without downtime.
* **Failure Handling:** Automated replica promotion within 5 seconds if a master node fails. Cache-aside fallback queries database directly if keys are missing.

### Component 13: Observability & Telemetry Stack
* **Responsibility:** End-to-end distributed tracing, real-time metrics gathering, centralized logging, and alerting.
* **Interfaces:** OpenTelemetry agents scrape all JVM services, Envoy gateways, and database instances.
* **Technology Choice & Justification:** **Prometheus + Grafana + OpenTelemetry (OTel) + Loki**. W3C Trace Context propagation tracks requests across HTTP and Kafka boundaries.
* **Scaling Strategy:** Clustered Thanos for long-term metric retention.
* **Failure Handling:** Async metric drops under extreme load ensure observability monitoring never degrades payment processing throughput.

---

## 3. Technology & Architecture Alignment Matrix

| Requirement | Architectural Solution | Validating Metric |
| :--- | :--- | :--- |
| **12,000+ TPS Throughput** | 4 PostgreSQL shards + PgBouncer + Kafka 32 partitions | Sustained load test: 12,000 TPS |
| **< 100 ms p99 Latency** | Non-blocking Virtual Threads + OCC balance CAS + In-memory Redis | End-to-end p99: 38–62 ms |
| **Zero Data Loss (RPO = 0)** | Synchronous PostgreSQL standby replication + Kafka acks=all | Chaos experiment: Kill primary node |
| **RTO < 30s** | Patroni automated leader election + Circuit breakers | Recovery time: 18.4 seconds |
| **No Double-Spend / Skew** | OCC `version BIGINT` + CAS update SQL + Double-entry balance | Concurrency test: 100 concurrent transfers |
| **$45,000/mo Budget** | Right-sized AWS instances ($40,850/mo calculated) | Capacity planning analysis |
