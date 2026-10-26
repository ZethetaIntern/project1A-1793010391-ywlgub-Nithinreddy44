# ADR-004: Inter-Service Communication Pattern (Synchronous REST vs. Asynchronous Event-Driven vs. Hybrid)

## Status
**Accepted**

## Context
PayScale Financial Technologies' microservices architecture requires communication patterns across ingestion, orchestration, payment execution, fraud scoring, notification dispatch, reconciliation, and audit recording.

During pre-Diwali testing under 50,000 concurrent simulated users (Bottleneck BN-003), synchronous blocking HTTP chains caused massive thread pool saturation. A single payment request chained synchronous HTTP calls across API Gateway -> Orchestrator -> Account Service -> Fraud Service -> Notification Service -> Database. Any transient latency spike in downstream notification or fraud scoring blocked application threads upstream, resulting in 40% request timeouts.

We must select an inter-service communication paradigm that satisfies our strict latency budget ($p50 \le 30\text{ ms}$, $p99 \le 100\text{ ms}$), decouples non-critical services, guarantees zero message loss, and handles 18,000 TPS burst traffic within our $45,000/month infrastructure budget.

## Decision
We have decided to adopt a **Hybrid Synchronous / Asynchronous Architecture**:

1. **Synchronous Core Path (Client Ingress, Fraud Pre-Check & Local Balance Authorization):**
   * *Client Ingestion:* Client to API Gateway communicates via HTTPS / HTTP/2 with JSON payloads.
   * *Fraud Detection:* Transaction Orchestrator executes a synchronous gRPC call to the Fraud Detection Service with a strict **15 ms deadline**. This pre-authorization check is essential to block fraudulent transactions before money moves.
   * *Balance Authorization & Outbox Commit:* Orchestrator executes local database ACID updates on the target PostgreSQL shard synchronously.
2. **Asynchronous Streaming Path (Event-Driven Backbone):**
   * *Post-Commit Event Propagation:* Once the database transaction commits with its local outbox entry, all downstream operations—Notification dispatch, ML feature store updates, Reconciliation audits, Merchant batch settlement aggregation, and RBI compliance logging—execute **asynchronously via Apache Kafka**.
   * *Notification Service:* Consumes Kafka events asynchronously to stream push notifications, webhooks, and WebSockets to 50,000+ connected clients without adding a single millisecond to the critical transaction path.
   * *Reconciliation Service:* Consumes Kafka event streams to continuously audit double-entry ledger equations out-of-band.

## Alternatives Considered

### 1. Pure Synchronous REST/gRPC Across All Services
* *Pros:* Simple conceptual model; immediate feedback from every service in the chain.
* *Rejection Rationale:* Creates tight temporal coupling and cascading failure chains. If the third-party SMS gateway or notification service experiences degradation, core P2P payments would stall, exhausting application thread pools and violating our 100 ms p99 latency SLA.

### 2. Pure Asynchronous Choreography (Fully Decoupled Event-Driven)
* *Pros:* Maximum throughput and total service decoupling.
* *Rejection Rationale:* In a pure choreographed architecture, the client receives an immediate `202 Accepted` response, and all payment authorization happens asynchronously. For retail P2P payments and merchant point-of-sale checkouts, users demand immediate confirmation (success or failure) within 500 ms at the checkout counter. Making balance authorization asynchronous complicates error reporting and user trust.

## Consequences

### Positive
* **Optimal Latency:** Critical payment confirmation returns in 32–45 ms because slow external I/O (notifications, SMS, deep fraud retraining) is removed from the request thread.
* **Resilience:** If the Notification Service or Audit Service temporarily crashes, transactions continue processing uninterrupted; Kafka safely buffers events until services recover.
* **Bounded Blast Radius:** External partner timeouts cannot exhaust core banking application thread pools.

### Trade-offs & Risks
* **Eventual Consistency Downstream:** Client notifications and dashboard balance graphs exhibit a 50–200 ms propagation delay following the HTTP 200 OK receipt.
* **Dual Monitoring:** Requires monitoring both HTTP/gRPC request latency and Kafka consumer lag.

## Compliance
This hybrid approach complies with **RBI Digital Payment Security Directives**:
* Critical authorization and balance updates remain atomic and synchronous.
* Asynchronous audit logging preserves an immutable cryptographic record without introducing regulatory vulnerability.
