# Technology Evaluation: Distributed Infrastructure Stack

## 1. Evaluation Methodology & Scoring Framework

To select the architectural components capable of scaling PayScale Financial Technologies from 1,200 TPS to 12,000+ sustained TPS (and 18,000 burst TPS) under strict RBI regulatory constraints and a $45,000/month budget ceiling, we establish a standardized quantitative evaluation matrix.

Each technology candidate is evaluated across five weighted criteria scored on a 1–10 scale:
* **Performance & Scalability (Weight: 30%):** Write throughput, sub-100ms p99 latency support, horizontal scaling characteristics, and memory efficiency under 18,000 TPS bursts.
* **Operational Complexity & Reliability (Weight: 25%):** High-availability failover automation, clustering mechanics, backup/restore procedures, and operational friction.
* **Cost Efficiency (Weight: 15%):** Infrastructure resource footprint, licensing overhead, and hosting economics within the $45k/month ceiling.
* **Team Expertise & Ecosystem Fit (Weight: 20%):** Compatibility with existing JVM/PostgreSQL/Redis engineering competencies and local talent availability in India.
* **Community Support & Tooling (Weight: 10%):** Ecosystem maturity, documentation, client library stability, and monitoring integration.

---

## 2. Message Queue & Event Streaming Evaluation

### 2.1 Candidate Comparison Matrix

| Evaluation Criterion | Weight | Apache Kafka | RabbitMQ (Legacy) | Amazon SQS (FIFO) |
| :--- | :---: | :---: | :---: | :---: |
| **Performance & Scalability** | 30% | **9.5** (Append-only log, 32 partitions, zero-copy I/O) | **5.0** (Memory alarm lockup, ack backlog at 2,000 TPS) | **6.5** (3,000 TPS FIFO soft limit with batching) |
| **Operational Complexity** | 25% | **8.0** (KRaft eliminates Zookeeper; cluster management) | **7.5** (Erlang clustering, split-brain risk) | **9.5** (Fully managed AWS serverless) |
| **Cost Efficiency** | 15% | **8.5** ($3,600/mo for 3-broker cluster on EC2/MSK) | **8.0** ($2,400/mo clustered, but collapses at load) | **5.0** ($0.50/M requests $\approx$ $11,000+/mo$ at 85M/day) |
| **Team Expertise & Fit** | 20% | **8.5** (Java native client, Spring Kafka integration) | **8.0** (Existing legacy team usage) | **7.0** (AWS SDK, vendor lock-in) |
| **Community & Tooling** | 10% | **9.5** (Debezium CDC, Kafka Connect, Schema Registry) | **8.5** (Extensive plugins and AMQP tools) | **7.5** (AWS CloudWatch native, proprietary API) |
| **Weighted Total Score** | **100%** | **8.85 / 10.0** | **6.75 / 10.0** | **7.15 / 10.0** |

### 2.2 Analysis & Recommendation
* **RabbitMQ:** Failed during pre-Diwali testing (BN-002). The per-message acknowledgment overhead and Erlang garbage collection pauses introduce insurmountable consumer lag (> 30s) at high TPS.
* **Amazon SQS FIFO:** While operationally simple, SQS FIFO has an absolute partition throughput limitation of 300 msg/s per group (up to 3,000 msg/s with batching), which is insufficient for 18,000 TPS bursts. Furthermore, cost scales per request, resulting in prohibitive operational expenses ($11,000+/month) under 85 million daily transactions.
* **Apache Kafka (Selected):** Kafka's partitioned append-only log architecture decouples producers from consumers. With 32 partitions distributed across 3 brokers using KRaft consensus, Kafka comfortably sustains 50,000+ msg/sec with sub-5ms write latencies. Integration with the Transactional Outbox pattern guarantees exactly-once business processing.

---

## 3. Database Layer Evaluation

### 3.1 Candidate Comparison Matrix

| Evaluation Criterion | Weight | PostgreSQL 15 + Citus Sharding | CockroachDB | TiDB |
| :--- | :---: | :---: | :---: | :---: |
| **Performance & Scalability** | 30% | **9.0** (Native PG engine, hash sharding, PgBouncer) | **8.5** (Raft-replicated ranges, write latency overhead) | **8.0** (Separated compute/storage, multi-raft) |
| **Operational Complexity** | 25% | **8.0** (Patroni HA, standard pg_dump, well-understood) | **9.0** (Self-healing, automated rebalancing) | **7.0** (PD, TiKV, TiDB triple-layer orchestration) |
| **Cost Efficiency** | 15% | **9.0** (Open-source, standard EC2 instances $\approx$ $14,400/mo$) | **6.0** (High enterprise license cost or resource overhead) | **7.5** (Compute/storage separation adds instance overhead) |
| **Team Expertise & Fit** | 20% | **9.5** (Existing PG 15 SQL queries, functions, and DBAs) | **7.0** (Postgres wire compatibility, but dialect differences) | **6.0** (MySQL wire protocol; requires dialect rewrite) |
| **Community & Tooling** | 10% | **9.5** (Vast PG ecosystem, psql, Flyway, Debezium, pg_stat) | **8.0** (Growing community, proprietary features) | **7.5** (Strong Chinese tech community, PingCAP) |
| **Weighted Total Score** | **100%** | **8.90 / 10.0** | **7.75 / 10.0** | **7.25 / 10.0** |

### 3.2 Analysis & Recommendation
* **TiDB:** TiDB implements the MySQL wire protocol, which would require rewriting all existing PostgreSQL schemas, PL/pgSQL triggers, and JDBC configurations.
* **CockroachDB:** Offers excellent automated range-based rebalancing and serializable transactions via Raft consensus. However, multi-region distributed consensus adds 15–30 ms of commit latency per transaction, threatening our strict 30 ms p50 latency budget. Furthermore, enterprise licensing adds substantial costs that exceed our budget ceiling.
* **PostgreSQL 15 + Sharding via Citus / Application-Level Routing (Selected):** Maximizes our existing database administrator expertise and codebase investments. Utilizing 4 database shards multiplexed behind PgBouncer allows each shard to handle ~3,000 write TPS locally within 8–15 ms, comfortably achieving 12,000+ aggregate TPS with zero risk of dialect incompatibilities.

---

## 4. Cache & Distributed State Tier Evaluation

### 4.1 Candidate Comparison Matrix

| Evaluation Criterion | Weight | Redis Cluster (6 Nodes) | Memcached | Hazelcast IMDG |
| :--- | :---: | :---: | :---: | :---: |
| **Performance & Scalability** | 30% | **9.5** (In-memory single-thread per shard, sub-ms GET/SET) | **9.0** (Multi-threaded in-memory, simple key-value) | **8.5** (In-memory JVM grid, GC overhead under heavy load) |
| **Operational Complexity** | 25% | **8.5** (Automated slot rebalancing, Sentinel/Cluster HA) | **8.0** (Client-side hashing, no built-in clustering) | **7.0** (Complex split-brain resolution, JVM memory tuning) |
| **Cost Efficiency** | 15% | **8.5** (6x `r6g.xlarge` nodes $\approx$ $4,800/mo$ for 96 GB) | **9.0** (Low memory overhead, simple infrastructure) | **6.5** (High JVM heap memory footprint per GB of data) |
| **Team Expertise & Fit** | 20% | **9.5** (Existing Redis 7.0 knowledge, Spring Data Redis) | **8.0** (Simple API, but lacks data structures) | **7.0** (Specialized Java API, operational steep learning curve) |
| **Data Structures & Lua** | 10% | **10.0** (Hashes, Sorted Sets, atomic Lua scripts for rate limits) | **4.0** (Simple byte arrays only; no atomic script support) | **8.5** (Rich distributed collections, compute execution) |
| **Weighted Total Score** | **100%** | **9.15 / 10.0** | **7.80 / 10.0** | **7.55 / 10.0** |

### 4.2 Analysis & Recommendation
* **Memcached:** Provides high single-node throughput due to its multi-threaded architecture, but lacks complex data structures (hashes, sorted sets) and atomic server-side script execution (Lua), which are mandatory for our token-bucket rate limiter and atomic idempotency reservation.
* **Hazelcast:** A robust distributed in-memory data grid, but introduces significant JVM heap overhead and GC pause risks during high-throughput burst periods.
* **Redis Cluster (Selected):** Deploying a 6-node Redis Cluster (3 primaries, 3 read replicas) provides 96 GB total memory partitioned across 16,384 hash slots. Sub-millisecond latency for key lookups, atomic Lua scripting for rate limiting, and 24-hour idempotency key reservation perfectly align with our requirements.

---

## 5. Technology Selection Summary

```
┌─────────────────────────┬───────────────────────────────────────┬─────────────────────────┐
│ Layer                   │ Selected Technology                   │ Primary Justification   │
├─────────────────────────┼───────────────────────────────────────┼─────────────────────────┤
│ Event Streaming / MQ    │ Apache Kafka 3.6 (KRaft, 3 Brokers)   │ High throughput, EOS    │
│ Primary Relational DB   │ PostgreSQL 15 (4 Shards + PgBouncer) │ ACID, team expertise    │
│ In-Memory Cache & State │ Redis Cluster 7.2 (6 Nodes / 96 GB)   │ Sub-ms read, Lua scripts│
│ Core Application Engine │ Java 21 / Spring Boot 3               │ Virtual Threads, Type-safe│
│ API Gateway / Proxy     │ Envoy / Spring Cloud Gateway          │ Token bucket, mTLS      │
│ Observability           │ Prometheus + Grafana + OpenTelemetry  │ Standard metrics/traces │
└─────────────────────────┴───────────────────────────────────────┴─────────────────────────┘
```
