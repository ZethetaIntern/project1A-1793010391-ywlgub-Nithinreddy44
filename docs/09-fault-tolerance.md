# Fault Tolerance, Circuit Breakers & Chaos Engineering: PayScale HTTPE

## 1. Resilience Philosophy

In a financial transaction engine handling 12,000+ sustained TPS and 18,000 burst TPS, service dependencies will inevitably experience degradation, network partitions, or hardware crashes. A resilient architecture prevents localized component failures from cascading into catastrophic systemic collapse.

PayScale enforces a **Zero-Cascade Isolation Architecture** using:
1. **Circuit Breakers** protecting all synchronous RPC dependencies.
2. **Bulkhead Resource Isolation** partitioning compute and connection pools.
3. **Bounded Retries with Full Jitter Exponential Backoff**.
4. **Automated Chaos Engineering** continuously validating the 30-second RTO.

---

## 2. Circuit Breaker Topology & Configuration

All inter-service and downstream infrastructure calls are governed by sliding-window circuit breakers configured according to their criticality and fallback semantics:

| Circuit Breaker | Protected Dependency | Failure Threshold | Evaluation Window | Reset Timeout (Sleep) | Fallback Behavior |
| :--- | :--- | :---: | :---: | :---: | :--- |
| **CB-FRAUD** | Fraud Detection Service | 3 failures | 10 seconds | 15 seconds | Allow transactions < ₹5,000 with `MANUAL_REVIEW_FLAGGED`; require OTP for > ₹5,000 |
| **CB-NOTIFY** | Notification Service | 5 failures | 30 seconds | 60 seconds | Queue notification in local fallback buffer; proceed with payment without blocking |
| **CB-DB-PRIMARY**| PostgreSQL Primary Shard | 2 failures | 5 seconds | 10 seconds | Route read queries to synchronous standby; pause/queue writes in PgBouncer |
| **CB-DB-REPLICA**| Read Replica | 5 failures | 10 seconds | 30 seconds | Route read traffic to primary with strict rate limiting to protect write capacity |
| **CB-CACHE** | Redis Cluster | 3 failures | 5 seconds | 20 seconds | Bypass cache layer; execute queries directly against database read replicas |
| **CB-EXCHANGE** | FX Exchange Rate Service | 3 failures | 10 seconds | 30 seconds | Utilize last known cached exchange rate with a `STALE_RATE_WARNING` audit tag |
| **CB-SETTLEMENT**| Merchant Settlement Engine| 2 failures | 5 seconds | 60 seconds | Defer settlement batch to the next hourly processing cycle |

### Circuit Breaker State Transition Rules:
* **CLOSED $\to$ OPEN:** When the error percentage or failure count exceeds the threshold within the sliding window, the breaker trips to `OPEN`. All subsequent calls fail fast without attempting network I/O, invoking the fallback method immediately.
* **OPEN $\to$ HALF-OPEN:** After the configured reset timeout (e.g., 15s for CB-FRAUD), the breaker transitions to `HALF-OPEN`, permitting a limited canary probe (5 requests).
* **HALF-OPEN $\to$ CLOSED:** If all 5 canary requests succeed, the breaker returns to `CLOSED` and resets failure counters. If any canary fails, it trips back to `OPEN` for another sleep cycle.

---

## 3. Bulkhead Isolation Architecture

To prevent thread and memory starvation, PayScale isolates distinct business workloads into separate thread pools and connection groups:

| Workload Domain | Dedicated Thread Pool | Max Queue Depth | Dedicated Connection Pool | Failure Impact |
| :--- | :---: | :---: | :---: | :--- |
| **Core P2P Payments** | 64 Virtual Threads | 2,000 | 40 conns / shard | Isolated from batch and reporting spikes |
| **Merchant POS Checkouts**| 64 Virtual Threads | 2,000 | 30 conns / shard | High-priority checkout path |
| **Batch Settlements** | 8 Worker Threads | 500 | 15 conns / shard | Low priority; throttled during trading hours |
| **Reconciliation Audits** | 4 Worker Threads | 200 | 10 conns (Replica) | Zero impact on primary write pools |
| **Notification Push / WS**| Netty Event Loop | 10,000 | Redis Pub/Sub conns | Non-blocking socket descriptors |

---

## 4. Chaos Engineering Experiment Portfolio (5+ Experiments)

PayScale conducts continuous automated chaos experiments in staging and canary environments to empirically validate the **30-second Recovery Time Objective (RTO)** and **zero committed transaction loss (RPO = 0)**.

### CE-001: Primary Database Node Crash Under Peak Load
* **Hypothesis:** Killing the primary PostgreSQL node of Shard 1 while processing 12,000 TPS will trigger Patroni failover, promote the synchronous standby within 30 seconds, and result in zero loss of committed transactions.
* **Blast Radius:** Shard 1 accounts (~25% of total user accounts).
* **Injection Method:** `kill -9` on the primary PostgreSQL process during sustained 12,000 TPS Locust load test at $T+10\text{ min}$.
* **Expected System Behavior:** 
  1. PgBouncer pauses and buffers incoming client connections.
  2. Patroni detects missing DCS lease in 5s and promotes the synchronous standby.
  3. PgBouncer re-routes traffic to the promoted primary at $T+16\text{s}$.
  4. In-flight uncommitted queries fail with connection abort and retry safely via idempotency keys.
* **Success Criteria:** 
  * Zero committed transaction loss ($\text{RPO} = 0$).
  * Total failover recovery time $\le 30\text{ seconds}$ ($\text{RTO} \le 30\text{s}$).
  * End-to-end p99 latency during failover spike $\le 5\text{ seconds}$.
* **Rollback Procedure:** If failover does not complete within 45 seconds, the automated chaos harness restarts the primary node and restores DCS topology.

### CE-002: Kafka Broker Failure & 500ms Network Partition
* **Hypothesis:** Injecting 500 ms packet delay and taking down 1 of 3 Kafka brokers will not halt transaction processing or cause duplicate event publications.
* **Blast Radius:** Kafka event streaming cluster.
* **Injection Method:** Traffic Control (`tc qdisc add dev eth0 root netem delay 500ms`) and `docker stop kafka-broker-2`.
* **Expected System Behavior:**
  1. Producers buffer messages in local ring-buffers.
  2. Kafka leader election for affected partitions completes within 3 seconds across the remaining 2 in-sync brokers (`min.insync.replicas = 2`).
  3. Transactional outbox forwarder safely publishes pending events.
* **Success Criteria:** Zero message loss; consumer lag recovers to baseline (< 500 messages) within 2 minutes of partition resolution.
* **Rollback Procedure:** Reset `tc` network delay rules and restart `kafka-broker-2`.

### CE-003: Redis Cluster Node Eviction Churn & Memory Saturation
* **Hypothesis:** Saturating Redis cluster memory to trigger mass evictions will not cause application crashes; Cache-Aside queries will degrade gracefully to PostgreSQL read replicas without exceeding connection limits.
* **Blast Radius:** In-memory caching layer.
* **Injection Method:** Ingest 10 million dummy keys with zero TTL via `redis-benchmark` to force `volatile-lru` evictions.
* **Expected System Behavior:** Circuit breaker `CB-CACHE` activates if Redis response time exceeds 50 ms. Queries bypass Redis and read directly from read replicas behind PgBouncer.
* **Success Criteria:** Database connection limits never exceeded; overall p99 transaction latency remains $\le 200\text{ ms}$ during cache degradation.
* **Rollback Procedure:** Flush injected keys (`FLUSHDB ASYNC`) and verify Redis memory returns to < 40% utilization.

### CE-004: Termination of 50% Application Pods
* **Hypothesis:** Suddenly terminating half of the running Payment Service pods will not cause dropped transactions; AWS ALB connection draining and Kubernetes horizontal pod auto-scaling (HPA) will restore full capacity within 90 seconds.
* **Blast Radius:** Application stateless compute tier.
* **Injection Method:** Delete 6 of 12 running payment-service container pods simultaneously under 12,000 TPS.
* **Expected System Behavior:** ALB reroutes traffic to the surviving 6 pods. HPA detects CPU spike (> 80%) and provisions 6 replacement pods.
* **Success Criteria:** Error rate stays $\le 2\%$ during pod spin-up; new pods enter active service within 90 seconds.
* **Rollback Procedure:** Manually reset deployment replica count to 12.

### CE-005: 5-Second NTP Clock Skew Between Services
* **Hypothesis:** Introducing a 5-second clock skew between the API Gateway and Account Service will not cause duplicate transactions or invalid ledger balance calculations.
* **Blast Radius:** Timestamp-dependent authentication and event ordering.
* **Injection Method:** Modify system time on node 2 using `date -s "+5 seconds"`.
* **Expected System Behavior:** UUIDv7 generation uses monotonic sequence counters. Redis distributed locks rely on relative `PX` millisecond timeouts rather than absolute wall-clock times.
* **Success Criteria:** Zero duplicate financial transactions; no negative balance anomalies; system self-heals when NTP synchronizes.
* **Rollback Procedure:** Re-enable `chronyd` NTP synchronization daemon.
