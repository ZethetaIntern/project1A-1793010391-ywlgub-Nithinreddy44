# ADR-001: Message Queue Selection (Apache Kafka vs. RabbitMQ vs. Amazon SQS)

## Status
**Accepted**

## Context
PayScale Financial Technologies operates a financial transaction processing platform in India undergoing an infrastructure transition to support an upcoming Diwali festive sale. The legacy platform utilizes a single-node RabbitMQ 3.11 message broker to handle asynchronous transaction event distribution, notification dispatching, merchant settlement triggers, and audit logging.

During recent stress testing simulating 50,000 concurrent users using Locust (Bottleneck BN-002), the legacy RabbitMQ broker failed at approximately 2,000 TPS. High transaction arrival rates caused a catastrophic message acknowledgment backlog, resulting in consumer lag exceeding 30 seconds. As RabbitMQ's Erlang VM memory alarms triggered, incoming connections were blocked, message paging to disk degraded I/O, and in-flight transactions were dropped.

The target system demands sustained throughput of 12,000 TPS, burst capacity of 18,000 TPS, end-to-end p99 latency below 100 ms, strict FIFO ordering per customer account, and exactly-once processing semantics without message loss (RPO = 0). The solution must also support the Transactional Outbox pattern and integrate cleanly with consumer groups across our microservices architecture within a $45,000/month infrastructure ceiling.

## Decision
We have decided to replace the legacy single-node RabbitMQ broker with an **Apache Kafka 3.6+ cluster operating with KRaft consensus**.

The cluster topology consists of:
1. **Three Broker Nodes:** Deployed across three distinct Availability Zones in AWS Mumbai (`ap-south-1a`, `ap-south-1b`, `ap-south-1c`) on compute-optimized instances (`c6i.2xlarge`, 8 vCPU, 16 GB RAM, dedicated 500 GB gp3 NVMe storage per broker).
2. **Topic Architecture & Partitioning:** The core `transaction-events` topic is configured with **32 partitions**, utilizing the customer's `account_id` as the message key. This configuration guarantees strict per-account message ordering while distributing write and read load uniformly across all brokers.
3. **Durability & Replication Settings:** Topics are configured with `replication.factor = 3`, `min.insync.replicas = 2`, and producers configured with `acks = all`. This ensures zero data loss (RPO = 0) even during a catastrophic single-broker failure.
4. **Exactly-Once Semantics (EOS):** Application services will implement the **Transactional Outbox pattern** using Debezium CDC and Kafka Connect. Outbox events are committed atomically inside the local database transaction; Kafka producers utilize idempotent publishing (`enable.idempotence = true`) to prevent dual-write duplicates. Downstream consumers maintain idempotent deduplication caches in Redis.
5. **Dead Letter Queue (DLQ):** Failed messages are routed to dedicated DLQ topics (e.g., `transaction-events-dlq`) with exponential backoff and a 14-day retention window for operational forensics.

## Alternatives Considered

### 1. Clustered RabbitMQ (Quorum Queues)
* *Pros:* Preserves existing AMQP client code and familiar routing concepts (exchanges and bindings). Quorum queues provide Raft-based consensus.
* *Rejection Rationale:* RabbitMQ maintains per-message queue index states in RAM. When consumers slow down, queue depth spikes degrade memory performance and broker throughput drops precipitously. RabbitMQ cannot support the 50,000+ msg/sec write burst required for PayScale's Diwali campaign.

### 2. Amazon SQS FIFO
* *Pros:* Fully managed AWS service with zero operational maintenance and built-in message deduplication.
* *Rejection Rationale:* SQS FIFO imposes a strict throughput limit of 300 msg/s per message group (3,000 msg/s with maximum batching). To support 18,000 burst TPS, the architecture would require hundreds of fragmented FIFO queues, drastically increasing architectural complexity. Furthermore, at 85 million daily transactions, SQS per-request pricing exceeds $11,000/month, consuming over 24% of our entire infrastructure budget.

### 3. Apache Pulsar
* *Pros:* Decoupled compute (broker) and storage (BookKeeper) architecture with multi-tenancy and tiered storage.
* *Rejection Rationale:* Operating both Pulsar brokers, Apache BookKeeper bookies, and coordination nodes introduces extreme operational overhead for our team. The local talent pool for Apache Kafka in India is significantly deeper, reducing staffing and operational risk.

## Consequences

### Positive
* **Predictable High Throughput:** Sequential append-only disk I/O and zero-copy OS transfers (`sendfile`) comfortably handle 50,000+ msg/sec with sub-5ms write latencies.
* **Consumer Group Scalability:** 32 partitions allow linear horizontal scaling of consumer instances across our Payment, Account, Notification, and Fraud services.
* **Event Replayability:** Kafka's configurable 7-day retention allows downstream analytics and reconciliation services to replay historical event streams to recover from service outages or software regressions.

### Trade-offs & Risks
* **Operational Complexity:** Operating Kafka requires monitoring partition consumer lag, disk utilization, and JVM garbage collection.
* **Partition Rebalancing Pauses:** Adding new partitions or consumer rebalancing can cause brief processing pauses if cooperative sticky assignors are not properly configured.

## Compliance
This decision complies directly with the **Reserve Bank of India (RBI) Master Direction on Digital Payment Security Controls (2021)**:
* Zero data loss (RPO = 0) for committed transactions is maintained via synchronous multi-AZ replica quorums (`min.insync.replicas = 2`).
* All Kafka broker instances and storage volumes are hosted strictly within the Indian territory (`ap-south-1` region), enforcing absolute data localization.
* In-flight encryption via TLS 1.3 and at-rest encryption via AWS KMS customer-managed keys (AES-256) ensure full regulatory compliance.
