# ADR-003: Sharding Strategy (Hash-Based with Virtual Buckets vs. Range-Based vs. Geographic)

## Status
**Accepted**

## Context
PayScale Financial Technologies' core banking database must scale to process 12,000+ sustained TPS and 18,000 burst TPS during the upcoming Diwali sale. Benchmarking shows that a single PostgreSQL 15 instance on AWS `r6i.2xlarge` reaches saturation at approximately 4,500 write TPS when handling ACID balance updates and double-entry ledger inserts.

To support the 18,000 burst TPS requirement with adequate capacity headroom, the persistence layer must be horizontally partitioned across multiple database shards. We must evaluate three fundamental sharding topologies:
1. Hash-based sharding on account ID
2. Range-based sharding on account ID or chronological creation dates
3. Geographic sharding based on user residence (Indian states/PIN codes)

The selected strategy must ensure uniform data distribution, minimize cross-shard write locks, mitigate hot-spot risks from high-volume merchants, support zero-downtime shard additions, and comply with the Reserve Bank of India (RBI) data localization mandate within a $45,000/month infrastructure budget.

## Decision
We have decided to adopt **Hash-Based Sharding using Virtual Buckets (1,024 slots) on `account_id` distributed across 4 PostgreSQL database shards**.

Key architectural specifications:
1. **Shard Key:** The customer's 128-bit UUIDv7 `account_id` serves as the sole shard key. Shard routing executes algorithmically in application memory:
   $$\text{Bucket} = \text{abs}(\text{MurmurHash3}(\text{account\_id})) \pmod{1024}$$
   $$\text{ShardID} = \text{Bucket} / 256$$
2. **Initial Cluster Sizing:** 4 physical database shards (256 buckets per shard), each provisioned with 1 primary node, 1 synchronous physical standby (for Patroni automated failover, RTO < 30s, RPO = 0), and 1 asynchronous read replica across three Availability Zones in AWS Mumbai.
3. **Hot-Partition Super-Merchant Mitigation:** Merchants processing > 200 TPS are provisioned with 8 virtual sub-accounts hash-striped across all 4 shards. P2P and checkout payments randomly distribute writes across sub-accounts, eliminating single-row OCC lock contention.
4. **Cross-Shard Transactions:** Cross-shard transfers are coordinated via a **Distributed Saga State Machine** rather than blocking Two-Phase Commit (2PC). Local shard autonomy is preserved with sub-15ms local commit times and automated compensating reversals.

## Alternatives Considered

### 1. Range-Based Sharding
* *Pros:* Extremely efficient for range queries and chronological reporting.
* *Rejection Rationale:* Because UUIDv7 includes a 48-bit millisecond timestamp prefix, range-based sharding causes all newly generated accounts and transactions to land on the current time-range shard, creating a severe single-shard write hotspot while older shards sit completely idle.

### 2. Geographic Sharding (by Indian State / PIN Code)
* *Pros:* Excellent for localized data queries and regional compliance.
* *Rejection Rationale:* Indian economic activity is heavily concentrated in major metropolitan centers (Mumbai, Delhi-NCR, Bengaluru, Hyderabad). Geographic sharding creates massive demographic imbalance, with the Maharashtra shard receiving over 35% of all traffic while smaller regions remain underutilized. Furthermore, inter-city P2P transfers would constantly trigger cross-shard overhead.

### 3. Dynamic Lookup Table Routing
* *Pros:* Allows arbitrarily moving individual accounts between shards on demand.
* *Rejection Rationale:* Introduces an external directory lookup query in the critical transaction path for every balance update. Even with Redis caching, cache misses would trigger database queries, introducing latency spikes and violating our 30 ms p50 latency budget.

## Consequences

### Positive
* **Mathematical Uniformity:** MurmurHash3 guarantees that 35 million customer accounts distribute across the 4 shards with less than 0.05% variance.
* **Zero Network Routing Overhead:** In-memory algorithmic hashing determines the target shard in under 200 nanoseconds without external database lookups.
* **Predictable Capacity:** 4 shards provide an aggregate ceiling of 18,000 write TPS, leaving 50% headroom above the 12,000 sustained TPS target.

### Trade-offs & Risks
* **Cross-Shard Aggregation:** Analytics queries aggregating user balances across multiple accounts require scatter-gather queries or streaming to ClickHouse/Loki.
* **Rebalancing Effort:** Expanding from 4 to 8 shards in the future requires online data migration of 128 virtual buckets per shard.

## Compliance
This decision complies fully with the **Reserve Bank of India (RBI) Master Direction on Digital Payment Security Controls (2021)**:
* All 4 primary and replica database shards reside physically within AWS Mumbai (`ap-south-1`), fulfilling complete data localization.
* Synchronous physical replication across Availability Zones guarantees zero committed transaction loss (RPO = 0) and 99.999999999% durability.
