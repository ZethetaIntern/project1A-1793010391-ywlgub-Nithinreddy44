# ADR-005: Cache Invalidation Strategy (Cache-Aside with Jittered TTL vs. Write-Through vs. Write-Behind)

## Status
**Accepted**

## Context
PayScale Financial Technologies employs a 6-node Redis Cluster (96 GB total memory) to cache customer account balances, active fraud rules, exchange rates, and client idempotency keys.

During pre-Diwali stress testing (Bottleneck BN-004), the single-node Redis cache experienced severe cache churn: hit ratios dropped from 92% to 61% under heavy load, and evictions triggered database read storms. In a high-throughput financial system processing 12,000+ TPS, serving stale balance data can cause false transaction rejections or user confusion, while overly aggressive invalidation can overwhelm database read replicas.

We must select a caching pattern that preserves financial correctness, achieves sub-millisecond read response times, mitigates thundering herd risks, and stays within our $45,000/month infrastructure budget.

## Decision
We have decided to adopt a **Cache-Aside Pattern with Jittered TTL and Immediate Write Eviction (Delete-on-Update)** for account balances, combined with **Deterministic 24-Hour TTLs for Idempotency Keys**.

Key implementation rules:
1. **Account Balance Read Path (Cache-Aside):**
   * Service checks Redis: `GET account:balance:{account_id}`.
   * If Cache Hit: Return cached balance (latency: < 1 ms).
   * If Cache Miss: Query PostgreSQL read replica, populate Redis with a **jittered TTL** of $60 \text{ s} \pm \text{random}(0, 15\text{ s})$, and return the value. Jittering prevents synchronized mass expiration (thundering herd problem).
2. **Account Balance Write Path (Immediate Eviction):**
   * When an account balance is updated in PostgreSQL (via debit or credit), the application **immediately evicts (deletes)** the cache key in Redis:
     `DEL account:balance:{account_id}`
   * The cache is not populated until the next read request. This eliminates race conditions where concurrent writes overwrite the cache with stale out-of-order data.
3. **Idempotency Key Invalidation:**
   * Idempotency keys are cached with an immutable 24-hour TTL (`86,400 seconds`). Idempotency records are never deleted early; they naturally expire after the 24-hour window per the project specification.
4. **Fraud Rule Cache (Pub/Sub Invalidation):**
   * Configurable fraud rules are cached locally in application JVM memory (Guava / Caffeine) with a 5-minute fallback TTL. When an administrator updates a rule, the Fraud Service publishes an invalidation event over Redis Pub/Sub (`channel:fraud_rules_updated`), triggering instantaneous cross-cluster cache eviction across all running pods within 50 ms.

## Alternatives Considered

### 1. Write-Through Caching
* *Pros:* Cache is always updated immediately; subsequent reads are guaranteed hits.
* *Rejection Rationale:* In a distributed system with multiple concurrent workers, two competing transactions $T_1$ and $T_2$ could update the database in order $(T_1 \to T_2)$, but network latency could cause their cache updates to arrive in reverse order $(T_2 \to T_1)$. This leaves the cache holding stale data from $T_1$ indefinitely until the next write. Eviction (Delete-on-Update) completely avoids this concurrency race.

### 2. Write-Behind (Write-Back) Caching
* *Pros:* Extreme write performance; transactions write only to memory and flush asynchronously to the database.
* *Rejection Rationale:* Unacceptable for core banking transactions. If a Redis node crashes before dirty balance records are flushed to PostgreSQL, committed financial data is permanently lost, violating our zero data loss (RPO = 0) and ACID requirements.

## Consequences

### Positive
* **Financial Safety:** Immediate cache eviction guarantees that subsequent reads never serve stale account balances after a transaction commits.
* **Thundering Herd Immunity:** Jittered TTLs spread cache re-population uniformly across time, preventing sudden database query spikes.
* **Sub-millisecond Latency:** 90%+ of account balance balance-inquiry queries are served directly from Redis in < 1 ms.

### Trade-offs & Risks
* **Cache Miss Overhead:** The first read request following an account balance mutation incurs a 3–5 ms latency hit to fetch the updated state from PostgreSQL.
* **Transient Discrepancy:** During the brief window (< 10 ms) between database commit and Redis deletion, an aggressive client read could observe the prior balance.

## Compliance
This caching strategy complies with **RBI Digital Payment Security Directives**:
* Permanent financial records remain safely anchored in PostgreSQL ACID storage.
* Memory caching serves purely as an accelerator; no uncommitted financial state resides solely in volatile memory.
