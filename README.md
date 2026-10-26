# PayScale High-Throughput Transaction Processing Engine (HTTPE)
### *Next-Generation Distributed Core Banking & Payment Backbone*

> **STRICTLY PRIVATE AND CONFIDENTIAL - NOT FOR CIRCULATION**  
> *This Project is designed, developed, and administered solely by Zetheta Algorithms Private Limited ("Zetheta") for the purpose of assessing candidates, students, or event participants on behalf of engaging employers, institutes, or event organisers. All intellectual property, novel architectural mechanisms, and implementation artifacts are protected and assigned under the IP Transfer Agreement.*

---

## 1. Project Overview

**PayScale Financial Technologies** is a Mumbai-based fintech neo-bank operating in the Indian market, serving 12 million monthly active users (scaling to 35 million) and processing over 28 million daily transactions (scaling to 85 million). 

In response to the upcoming Diwali festive-season flash sales and marketing cashback promotions, this project establishes a resilient, horizontally scalable **High-Throughput Transaction Processing Engine (HTTPE)**. The engine is engineered to scale from a legacy baseline of **1,200 TPS** to **12,000+ sustained TPS** and **18,000 burst TPS**, while meeting stringent financial correctness, double-entry ledger accounting, and sub-100ms p99 latency guarantees under the Reserve Bank of India (RBI) regulatory framework.

### Key Performance & Reliability Metrics
* **Sustained Throughput:** 12,000 Transactions Per Second (TPS)
* **Burst Throughput:** 18,000 TPS for 15-minute peak intervals
* **End-to-End Latency:** $p50 \le 30\text{ ms}$, $p99 \le 100\text{ ms}$, $p99.9 \le 250\text{ ms}$
* **Availability Objective:** 99.99% uptime ($\le 52.6\text{ minutes}$ downtime/year)
* **High Availability & Recovery:** Single-component $\text{RTO} \le 30\text{ seconds}$, committed-transaction $\text{RPO} = 0$
* **Concurrent Real-time Connections:** 50,000 active WebSocket client connections
* **Monthly Infrastructure Cost:** $\$40,850\text{/month}$ (Strictly under the $\$45,000\text{/month}$ ceiling)

---

## 2. Target Architecture Overview

The system architecture implements a modular, distributed topology across 13 core subsystems:

```
[ Client Apps & Webhooks ]
           │ (HTTPS / WSS / TLS 1.3)
           ▼
[ API Gateway Layer (Envoy / Spring Cloud Gateway) ] ── (Token Bucket Rate Limiter)
           │
           ▼
[ L7 Load Balancers (AWS NLB/ALB) ]
           │
     ┌─────┴──────────────────────────────────────────────────────┐
     │                     Application Tier                       │
     ▼                                                            ▼
[ Payment Processing Service ]                         [ Account & Balance Service ]
(Idempotency & Payload Match)                          (OCC Version Check & Double-Entry)
     │                                                            ▲
     │                                                            │
     ▼                                                            │
[ Transaction Orchestrator (Saga FSM) ] ──────────────────────────┘
     │                                     
     ├──► [ Fraud Detection Service ] (15ms SLA, Redis Feature Store)
     │
     ├──► [ Transactional Outbox ] ──► [ Apache Kafka Event Streaming (32 Partitions) ]
     │                                             │
     │                 ┌───────────────────────────┼───────────────────────────┐
     │                 ▼                           ▼                           ▼
     │      [ Notification Service ]    [ Reconciliation Svc ]    [ Audit & Compliance ]
     │      (WebSockets & Webhooks)     (Double-Entry Ledger)     (Immutable Append-Only)
     │
     ▼
[ Redis Cluster (6 Nodes / 96GB) ] ──► Idempotency Cache, Distributed Locks & Rate Limits
     │
     ▼
[ Distributed Database Tier ]
     ├── PgBouncer Connection Poolers (2,000+ client connections)
     └── Sharded PostgreSQL 15 (4 Shards x 3 Replicas with Citus / Consistent Hashing)
```

---

## 3. Financial Correctness Principles

1. **ACID Transactions:** Atomic balance updates and ledger entries executed in isolated database transactions.
2. **Double-Entry Bookkeeping:** Every monetary transaction consists of equal debit and credit entries; platform ledger sum strictly balances to zero ($\sum \text{Debits} - \sum \text{Credits} = 0$).
3. **Dual-Balance Tracking:** Independent `available_balance` (for immediate authorization) and `ledger_balance` (for cleared settlement) per account.
4. **Optimistic Concurrency Control (OCC):** High-throughput balance modifications executed via version counters (`version BIGINT`) and Compare-And-Swap (CAS) SQL predicates, eliminating pessimistic row-level deadlocks.
5. **Durable Idempotency:** Client-generated `Idempotency-Key` (UUIDv7) with a 24-hour TTL in Redis and PostgreSQL. Repeated identical requests return cached responses with zero duplicate financial side effects. Conflicting payloads return HTTP 409 Conflict.
6. **Compensating Reversals:** Distributed multi-account transfers managed via a Saga Orchestrator with automatic compensating reversals executed within the **30-second RTO** target upon failure.

---

## 4. Repository Directory Structure

```
.
├── README.md                          # Master project documentation and confidentiality notice
├── SELF-ASSESSMENT.md                 # 1000-point rubric self-evaluation and justification
├── REFLECTION.md                      # 500-word engineering reflection on trade-offs and learnings
├── docker-compose.yml                 # Local development & testing stack
│
├── docs/                              # Formal Architecture & Design Deliverables
│   ├── 01-scenario-analysis.md        # PayScale scenario, 10x surge, and bottleneck analysis
│   ├── 02-technology-evaluation.md    # Multi-criteria scoring matrices for DB, MQ, and Cache
│   ├── 03-high-level-design.md        # Comprehensive HLD of all 13 architecture components
│   ├── 04-data-flow-design.md         # P2P payment, batch settlement, and failover flows
│   ├── 05-database-schema.md          # Schema specifications, indexes, and partitioning strategy
│   ├── 06-sharding-strategy.md        # Shard key mathematical distribution, cross-shard 2PC/Saga
│   ├── 07-message-queue-topology.md   # Kafka topic structure, partition calculations, and Outbox EOS
│   ├── 08-concurrency-control.md      # OCC balance update mechanism, formal proof, and isolation levels
│   ├── 09-fault-tolerance.md          # Circuit breakers, bulkheads, retries, and chaos experiments
│   ├── 10-api-specification.md       # API design, pagination, error catalog, and rate-limiting
│   ├── 11-load-testing-strategy.md    # 8 load test scenarios (LT-001..LT-008) and performance budget
│   ├── 12-capacity-planning.md        # Resource sizing for 1.2K, 12K, and 24K TPS within $45k budget
│   ├── 13-fmea.md                     # FMEA table with 20+ failure modes, RPN scoring, and mitigations
│   └── 14-architecture-review-deck.md # 15-minute ARB presentation slides and defense script
│
├── adrs/                              # Architecture Decision Records
│   ├── 001-message-queue-selection.md # Kafka vs RabbitMQ vs Amazon SQS
│   ├── 002-database-selection.md      # PostgreSQL+Citus vs CockroachDB vs TiDB
│   ├── 003-sharding-strategy.md       # Hash-based vs Range-based vs Geographic sharding
│   ├── 004-communication-pattern.md  # Synchronous REST vs Asynchronous Event-Driven vs Hybrid
│   ├── 005-cache-invalidation.md      # Cache-Aside with TTL vs Write-Through vs Write-Behind
│   └── 006-concurrency-control-occ.md # Optimistic Concurrency Control vs Pessimistic Locking
│
├── diagrams/                          # Architecture & Sequence Diagrams
│   ├── system-architecture.drawio     # XML source for the 13-component system architecture
│   ├── system-architecture.png        # Rendered high-resolution architecture diagram
│   ├── p2p-payment-flow.puml          # PlantUML sequence diagram for P2P transaction flow
│   ├── batch-settlement-flow.puml     # PlantUML sequence diagram for batch merchant settlement
│   ├── failover-flow.puml             # PlantUML sequence diagram for database/service failover
│   └── circuit-breaker-fsm.puml       # PlantUML state machine diagram for circuit breakers
│
├── schemas/                           # Database Definitions & ERD
│   ├── ddl/                           # Production SQL DDL scripts with constraints & indexes
│   │   ├── 001-accounts.sql
│   │   ├── 002-transactions.sql
│   │   ├── 003-ledger-entries.sql
│   │   ├── 004-users.sql
│   │   ├── 005-transaction-events.sql
│   │   ├── 006-fraud-rules.sql
│   │   ├── 007-merchant-settlements.sql
│   │   └── 008-notification-log.sql
│   └── erd/
│       ├── erd-diagram.dbml           # DBML schema definition for dbdiagram.io
│       └── erd-diagram.png            # Rendered entity-relationship diagram
│
├── pseudocode/                        # Core Algorithmic Implementations
│   ├── occ-balance-update.py          # OCC balance update with CAS and retry loop
│   ├── saga-orchestrator.py           # Distributed transaction orchestrator with compensation
│   ├── circuit-breaker.py             # Sliding-window circuit breaker state machine
│   └── idempotency-handler.py         # Distributed atomic idempotency lock and payload validation
│
├── api/                               # API Definitions & Test Collections
│   ├── openapi.yaml                   # OpenAPI 3.0 specification for all endpoints
│   └── postman-collection.json        # Postman test collection with sample payloads
│
├── load-tests/                        # Performance Engineering Artifacts
│   ├── performance-budget.md          # 100ms p99 latency allocation breakdown
│   └── scenarios/                     # Locust & k6 load testing scripts
│       ├── lt-001-baseline.py         # 1,200 TPS baseline test
│       ├── lt-002-target-load.py      # 12,000 TPS sustained load test
│       ├── lt-003-burst-capacity.py   # 18,000 TPS peak burst test
│       ├── lt-006-failover-load.py    # Database failover during peak load
│       └── lt-007-mixed-workload.py   # Realistic traffic distribution test
│
├── httpe-engine/                      # Production Java 21 / Spring Boot 3 Engine
│   ├── pom.xml
│   ├── src/main/java/com/payscale/httpe/
│   │   ├── api/                       # REST Controllers & DTOs
│   │   ├── config/                    # Security, Kafka, Redis, and DB configurations
│   │   ├── domain/                    # Entities, Enums, and Value Objects
│   │   ├── repository/                # Spring Data JPA / Sharded JDBC Repositories
│   │   └── service/                   # Business Services (Account, Payment, Saga, Ledger)
│   └── src/test/java/com/payscale/httpe/ # Automated Unit, Concurrency, and Integration Tests
│
└── simulation-titan/                  # "TRANSACTION TITAN: The Scale Architect" Dashboard
    ├── package.json
    ├── index.html
    └── src/                           # Live traffic controls, metrics, chaos injection & cost tracker
```

---

## 5. Quickstart & Verification

### Prerequisites
* Java JDK 21 or later
* Apache Maven 3.9+ (or use preconfigured `./mvnw`)
* Node.js v20+ and npm
* Docker & Docker Compose (for containerized infrastructure)

### 1. Build and Test the Core Engine
```bash
cd httpe-engine
/Users/nithinkumar/.maven/apache-maven-3.9.6/bin/mvn clean test
```

### 2. Launch Local Multi-Component Environment
```bash
docker-compose up -d
```
Services exposed:
* **API Gateway & HTTPE Engine:** `http://localhost:8080`
* **Swagger UI Documentation:** `http://localhost:8080/swagger-ui.html`
* **Prometheus Metrics:** `http://localhost:9090`
* **Grafana Dashboards:** `http://localhost:3000` (admin/admin)
* **PostgreSQL Primary:** `localhost:5432`
* **Redis Cluster:** `localhost:6379`
* **Apache Kafka Broker:** `localhost:9092`

### 3. Launch "TRANSACTION TITAN: The Scale Architect" Simulation
```bash
cd simulation-titan
npm install
npm run dev
```
Open `http://localhost:5173` to interact with real-time throughput meters, flash-sale load generators, fault-injection switches, and financial reconciliation reports.
