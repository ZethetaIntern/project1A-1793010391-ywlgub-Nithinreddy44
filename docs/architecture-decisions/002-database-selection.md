# ADR-002: Database Selection (PostgreSQL 15 + Citus Sharding vs. CockroachDB vs. TiDB)

## Status
**Accepted**

## Context
PayScale Financial Technologies' legacy core banking and transaction processing system relies on a single-node PostgreSQL 15 primary database instance running on an AWS `r5.2xlarge` instance (8 vCPU, 64 GB RAM).

During pre-Diwali stress testing simulating 50,000 concurrent users using Locust (Bottleneck BN-001 & BN-006), the single-node database suffered severe connection pool exhaustion at 1,800 TPS when client requests exceeded `max_connections = 200`. Query queue depths rose above 500 requests, and row-level locking on the `accounts` table produced a 15% deadlock rate (`40P01`) under concurrent P2P transfers.

The target system demands sustained transaction throughput of 12,000 TPS with burst peaks of 18,000 TPS, an end-to-end write latency under 15 ms at the database layer, strict ACID guarantees, double-entry bookkeeping constraints, zero negative balances, and compliance with the RBI mandate requiring data residency in India. The infrastructure budget ceiling is $45,000/month, of which the database layer must remain within $16,000/month.

## Decision
We have decided to scale our persistence tier by adopting **PostgreSQL 15 with Horizontal Sharding via Citus and Transaction Connection Pooling via PgBouncer**.

The architecture comprises:
1. **Sharded Topology:** A distributed cluster of **4 primary PostgreSQL shards**, each paired with a synchronous physical standby replica (for zero data loss failover via Patroni/Raft) and an asynchronous read replica across three Availability Zones in AWS Mumbai (`ap-south-1`).
2. **Connection Pooling via PgBouncer:** Each database shard is fronted by dedicated PgBouncer instances running in `transaction` pooling mode. This allows the application cluster to maintain over 2,000 concurrent client connections while keeping backend active PostgreSQL connections bounded to 100 per node, eliminating connection exhaustion.
3. **Shard Key Strategy:** Sharding is executed on `shard_key = abs(hashtext(account_id::text)) % 4`. This uniform hash distribution eliminates geographic and demographic hotspots while keeping all account balance records, account events, and local outbox tables colocated on the same shard.
4. **Cross-Shard Transactions:** Cross-shard P2P transfers are coordinated using a **Distributed Saga Pattern** managed by the Transaction Orchestrator rather than heavyweight distributed two-phase commits (2PC), preserving local shard autonomy and high throughput.
5. **Time-Based Partitioning:** The high-volume `transactions` and `ledger_entries` tables utilize native PostgreSQL declarative range partitioning by month (`created_at`), allowing fast vacuuming, efficient indexing, and seamless archiving.

## Alternatives Considered

### 1. CockroachDB
* *Pros:* Native distributed SQL with automatic range-based sharding, Raft consensus per range, and serializable isolation by default.
* *Rejection Rationale:* While architecturally elegant, CockroachDB introduces two major drawbacks for PayScale:
  1. *Latency Overhead:* Multi-node Raft consensus across distributed ranges adds 15–30 ms to every write commit, which consumes virtually our entire 30 ms p50 latency budget.
  2. *Cost:* CockroachDB Dedicated or Enterprise licensing for the required scale would exceed $22,000/month, immediately violating our $16,000/month database budget ceiling.

### 2. TiDB (PingCAP)
* *Pros:* Distributed HTAP database separating compute (TiDB) and storage (TiKV) with horizontal scaling.
* *Rejection Rationale:* TiDB natively supports the MySQL protocol and dialect. Adopting TiDB would require a complete migration and rewrite of our existing PostgreSQL schemas, custom PL/pgSQL audit triggers, JSONB queries, and Spring Data JPA entities, which is unachievable within the 15-day Diwali delivery window.

### 3. Pure Cloud Native NoSQL (DynamoDB / Cassandra)
* *Pros:* Proven ability to handle hundreds of thousands of writes per second with sub-10ms latency.
* *Rejection Rationale:* NoSQL databases lack multi-table ACID transactions, foreign key constraints, and standard SQL aggregation capabilities. Building double-entry ledger verification, multi-account debits/credits, and balance constraint enforcement entirely in application logic increases the risk of financial discrepancies, violating RBI auditability requirements.

## Consequences

### Positive
* **High Write Throughput:** Dividing the 12,000 TPS target across 4 shards means each PostgreSQL primary node only needs to process ~3,000 TPS—a workload well within PostgreSQL 15's capability with NVMe storage and PgBouncer.
* **Leverage Existing Skills:** PayScale's DBA and engineering teams retain 100% of their operational familiarity with PostgreSQL tooling (`pg_stat_statements`, `pg_dump`, `wal-g`, `EXPLAIN ANALYZE`).
* **Cost Efficiency:** Running 4 shards with primary, sync standby, and read replica on AWS EC2 instances (`r6i.2xlarge`) costs approximately $14,400/month, remaining comfortably within our budget.

### Trade-offs & Risks
* **Cross-Shard Coordination:** Queries aggregating balances across multiple accounts belonging to different shards require scatter-gather queries or asynchronous reconciliation jobs.
* **Operational Shard Management:** Rebalancing or adding a 5th shard in the future requires hash-slot migration.

## Compliance
This decision complies with all regulatory directives issued by the **Reserve Bank of India (RBI)**:
* **Data Residency:** All PostgreSQL primary and replica nodes reside exclusively in the AWS Mumbai (`ap-south-1`) region.
* **Auditability & Integrity:** PostgreSQL's Write-Ahead Logging (WAL) with continuous archiving to encrypted Amazon S3 buckets guarantees eleven 9s (99.999999999%) durability and point-in-time recovery (PITR) with RPO = 0.
* **Encryption:** Transparent Data Encryption at rest (AES-256) and TLS 1.3 in-transit encryption protect all account and transaction data.
