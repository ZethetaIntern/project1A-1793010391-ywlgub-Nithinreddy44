# Message Queue Topology & Event Streaming Design: PayScale HTTPE

## 1. Event Bus Architecture & Partitioning Topology

Apache Kafka 3.6+ operating with KRaft consensus serves as PayScale's high-throughput event streaming backbone. It decouples high-speed synchronous payment ingestion from downstream notifications, analytics, fraud re-scoring, and merchant settlement pipelines.

### 1.1 Kafka Cluster Specifications
* **Brokers:** 3 Dedicated Brokers (`c6i.2xlarge`, 8 vCPU, 16 GB RAM, 500 GB NVMe gp3 disk).
* **Consensus Engine:** KRaft (Kafka Raft Metadata Mode), eliminating external ZooKeeper dependencies.
* **Geographic Placement:** Distributed across AWS Mumbai Availability Zones (`ap-south-1a`, `ap-south-1b`, `ap-south-1c`).
* **Throughput Sizing:** Cluster is sized to sustain 60,000 msg/sec write throughput with sub-5ms producer latency.

---

## 2. Topic Architecture & Partition Calculations

### 2.1 Partition Calculation Formula
The required number of partitions $P$ for an event topic is determined by:
$$P = \max\left(\left\lceil \frac{\text{Target Throughput}}{\text{Single Partition Producer Rate}} \right\rceil, \left\lceil \frac{\text{Target Throughput}}{\text{Single Partition Consumer Rate}} \right\rceil\right)$$

* **Target Throughput:** 18,000 msg/sec (Burst Peak).
* **Producer Rate per Partition:** ~10,000 msg/sec (using Kafka producer batching with `linger.ms = 5`).
* **Consumer Rate per Partition:** ~600 msg/sec (accounting for downstream business processing, database checks, and network serialization).
* **Calculation:**
  $$P = \left\lceil \frac{18,000 \text{ msg/sec}}{600 \text{ msg/sec}} \right\rceil = 30 \text{ partitions}$$
* **Configured Partitions:** We round up to **32 partitions** (a clean power of two for optimal hash distribution across 3 brokers: 11, 11, and 10 partitions per broker).

### 2.2 Complete Topic Specifications

| Topic Name | Partitions | Replication Factor | Min In-Sync Replicas | Key Strategy | Retention Policy | Purpose |
| :--- | :---: | :---: | :---: | :--- | :--- | :--- |
| `transaction-events` | 32 | 3 | 2 | `account_id` | 7 Days (Compact + Delete) | Core payment events (`INITIATED`, `COMPLETED`, `FAILED`, `REVERSED`) |
| `notification-events` | 16 | 3 | 2 | `user_id` | 24 Hours | Dispatches WebSockets, push alerts, SMS receipts |
| `fraud-events` | 16 | 3 | 2 | `account_id` | 30 Days | Asynchronous feature store updates and ML model telemetry |
| `settlement-events` | 8 | 3 | 2 | `merchant_id` | 30 Days | Batch settlement triggers and completion notices |
| `audit-events` | 32 | 3 | 2 | `transaction_id` | 365 Days (Append-Only) | Immutable compliance log for RBI audits |
| `transaction-events-dlq`| 8 | 3 | 2 | `transaction_id` | 14 Days | Dead Letter Queue for poison messages and persistent errors |

---

## 3. Consumer Group Alignment & Processing Rates

To eliminate consumer lag (which caused the failure in RabbitMQ bottleneck BN-002), each consumer group is provisioned with worker thread pools that match or exceed partition counts:

| Consumer Group | Consumed Topic | Instance Count | Threads / Instance | Total Active Consumers | Max Processing Capacity |
| :--- | :--- | :---: | :---: | :---: | :--- |
| `cg-notification` | `notification-events` | 4 | 4 | 16 | 24,000 msg/sec |
| `cg-fraud-learning`| `fraud-events` | 4 | 4 | 16 | 20,000 msg/sec |
| `cg-reconciliation`| `transaction-events` | 8 | 4 | 32 | 32,000 msg/sec |
| `cg-audit-logger` | `audit-events` | 8 | 4 | 32 | 40,000 msg/sec |
| `cg-settlement` | `settlement-events` | 2 | 4 | 8 | 10,000 msg/sec |

* **Rebalance Strategy:** Consumer groups utilize `CooperativeStickyAssignor`, which ensures incremental, cooperative partition reassignment during pod scaling, eliminating "stop-the-world" consumer pauses.

---

## 4. Exactly-Once Semantics (EOS): The Transactional Outbox Pattern

A fundamental anti-pattern in distributed systems is the "dual-write" problem: writing to PostgreSQL and publishing to Kafka in the same application method cannot be atomically guaranteed. If the database commits but the Kafka publish fails (or the app crashes), the event is lost. If Kafka publishes first but the database rollback occurs, a phantom transaction is propagated.

```
                       APPLICATION TRANSACTION BOUNDARY
┌────────────────────────────────────────────────────────────────────────┐
│ 1. UPDATE accounts SET balance = balance - 1500 ...                    │
│ 2. INSERT INTO transactions (id, status='COMPLETED') ...               │
│ 3. INSERT INTO ledger_entries (...) ...                                │
│ 4. INSERT INTO outbox_events (id, aggregate_id, event_type, payload)   │
└────────────────────────────────────────────────────────────────────────┘
                                    │ (Atomically Committed)
                                    ▼
                     PostgreSQL WAL (Write-Ahead Log)
                                    │
                                    ▼
               Debezium CDC Connector / Polling Outbox Worker
                                    │ (Reads WAL Stream)
                                    ▼
                    Kafka Producer (acks=all, idempotent)
                                    │
                                    ▼
                Apache Kafka: `transaction-events` Topic
```

### Technical Workflow:
1. Every payment execution inserts an event row into `outbox_events` within the **same local ACID database transaction** as the account balance update.
2. A lightweight outbox forwarder (Debezium CDC connector reading the PostgreSQL Write-Ahead Log) captures committed outbox records and publishes them to Kafka with `acks = all` and `enable.idempotence = true`.
3. The event payload includes a unique `event_id` (UUIDv7) and `transaction_id`.
4. Downstream consumers maintain a 24-hour deduplication cache in Redis. Before processing an incoming message, consumers execute:
   $$\text{SETNX dedupe:consumer\_group:event\_id 1 EX 86400}$$
   If the key already exists, the consumer acknowledges the offset and skips redundant processing, guaranteeing **exactly-once business effects**.

---

## 5. Dead Letter Queue (DLQ) & Error Recovery

When a consumer encounters an unrecoverable business exception or persistent serialization error (poison pill), stalling the consumer partition is unacceptable.

```
[ Incoming Message from Kafka ]
               │
               ▼
   [ Process Business Logic ]
               │
     ┌─────────┴─────────┐
(Success)             (Failure)
     │                   │
     ▼                   ▼
[ Commit Offset ]  [ Retry Loop (Exponential Backoff: 100ms, 500ms, 2s) ]
                         │
               ┌─────────┴─────────┐
          (Success)           (Retries Exhausted = 3)
               │                   │
               ▼                   ▼
         [ Commit Offset ]   [ Publish to DLQ Topic (`transaction-events-dlq`) ]
                                   │
                                   ├── Include Error Stack Trace & Original Payload
                                   ├── Increment `dlq_messages_total` Metric
                                   └── Commit Offset on Source Partition
```

* **DLQ Monitoring:** Alertmanager fires a P2 alert if `dlq_messages_total` exceeds 10 messages within 5 minutes.
* **Reprocessing Tool:** Support engineers inspect DLQ messages via an administrative CLI and trigger replay back to the main topic after fixing upstream schema or service defects.
