# Load Testing Strategy, Benchmarks & Metrics: PayScale HTTPE

## 1. Load Testing Framework & Objectives

To validate that PayScale's redesigned distributed architecture satisfies the 12,000 sustained TPS and 18,000 burst TPS acceptance criteria, we establish a comprehensive load testing suite using **Locust** and **k6**.

Testing evaluates:
1. **Throughput & Concurrency:** Ability to handle 50,000 concurrent client sessions without connection dropouts.
2. **Latency Distributions:** Compliance with $p50 \le 30\text{ ms}$, $p99 \le 100\text{ ms}$, and $p99.9 \le 250\text{ ms}$.
3. **Resilience Under Chaos:** Zero committed data loss ($\text{RPO} = 0$) and $\text{RTO} \le 30\text{s}$ during node termination.
4. **Leak-Free Stability:** Memory and file descriptor stability over multi-hour soak tests.

---

## 2. Comprehensive Catalog of 8 Load Testing Scenarios

| Scenario ID | Scenario Name | Description | Target TPS | Duration | Warm-up / Cool-down | Pass/Fail Criteria |
| :--- | :--- | :--- | :---: | :---: | :---: | :--- |
| **LT-001** | Baseline Performance | Steady-state load matching legacy peak to establish baseline metrics | 1,200 TPS | 30 min | 5 min / 5 min | $p99 \le 35\text{ ms}$; Error rate $< 0.01\%$; CPU $< 25\%$ |
| **LT-002** | Target Sustained Load | Sustained target peak validating scaled distributed architecture | 12,000 TPS | 60 min | 10 min / 5 min | $p99 \le 100\text{ ms}$; Error rate $< 0.05\%$; Shard CPU $< 70\%$ |
| **LT-003** | Burst Capacity | 150% of target load simulating Diwali flash-sale checkout surge | 18,000 TPS | 15 min | 2 min / 3 min | $p99 \le 120\text{ ms}$; Error rate $< 0.1\%$; Zero DB connection errors |
| **LT-004** | Extended Soak Test | Extended operation at 80% capacity to detect slow memory/goroutine leaks | 9,600 TPS | 4 hours | 15 min / 15 min | Heap memory stable ($\le 5\%$ drift); Zero OOM errors; No unclosed sockets |
| **LT-005** | Gradual Ramp-Up | Linear step-up from 0 to 15,000 TPS to identify architectural inflection points | 0 $\to$ 15K | 30 min | 0 min / 5 min | Identifies knee of latency curve; no thread deadlock |
| **LT-006** | Failover Under Peak Load | 12K TPS with primary PostgreSQL killed at $T+10\text{ min}$ (Chaos CE-001) | 12,000 TPS | 30 min | 5 min / 5 min | Failover $\le 30\text{s}$; Zero committed data loss; Error rate $< 1.5\%$ during failover |
| **LT-007** | Realistic Mixed Workload | 60% P2P, 20% balance query, 10% merchant, 5% reversal, 5% account creation | 12,000 TPS | 60 min | 10 min / 5 min | $p50 \le 25\text{ ms}$, $p99 \le 90\text{ ms}$; Cache hit ratio $> 90\%$ |
| **LT-008** | Hot Partition Stress | High contention: 5,000 TPS directed at a single merchant with virtual sub-accounts | 5,000 TPS | 10 min | 1 min / 2 min | Zero deadlocks; OCC retry exhaustion $< 0.1\%$; Sub-account balance integrity |

---

## 3. Observability & Telemetry Metric Thresholds

During all load test runs, Prometheus scrapes performance metrics at 1-second intervals. The following metrics govern real-time test validation:

| Metric Name | Prometheus Type | Description & Labels | Critical Alert Threshold |
| :--- | :--- | :--- | :--- |
| `txn_requests_total` | Counter | Total requests by `status`, `type` | Error rate $> 1.0\%$ over 5 min |
| `txn_processing_duration_seconds` | Histogram | End-to-end latency by `type` | $p99 > 100\text{ ms}$ for 3 consecutive minutes |
| `txn_in_flight` | Gauge | Active concurrent transactions | $> 500$ concurrent per worker instance |
| `account_balance_update_retries` | Counter | OCC collision retry counts | Exhaustion rate $> 0.1\%$ per minute |
| `circuit_breaker_state` | Gauge | State: `0=CLOSED`, `1=OPEN`, `2=HALF_OPEN`| `OPEN` state $> 2$ minutes |
| `saga_state_transitions_total` | Counter | Transitions by `from_state`, `to_state` | Compensation rate $> 2\%$ over 5 min |
| `kafka_consumer_lag` | Gauge | Consumer lag by `topic`, `group`, `partition`| Lag $> 10,000$ messages for $> 1\text{ min}$ |
| `db_connection_pool_active` | Gauge | PgBouncer active conns by `shard` | $> 80\%$ utilization for $> 5\text{ min}$ |
| `db_query_duration_seconds` | Histogram | Execution time by `query_type`, `shard` | $p95 > 50\text{ ms}$ |
| `redis_memory_utilization` | Gauge | Redis memory ratio by `node` | $> 80\%$ of `maxmemory` |

---

## 4. Test Environment Infrastructure Configuration

To ensure load testing results are reproducible and reflect true distributed systems performance rather than local hardware constraints:
* **Load Generators:** 8 distributed Locust worker nodes deployed on AWS `c6i.4xlarge` (16 vCPU, 32 GB RAM each) generating up to 25,000 requests/sec.
* **Network Isolation:** Load generators, API Gateway, application cluster, Kafka brokers, and PostgreSQL shards communicate over AWS VPC with 100 Gbps Enhanced Networking and MTU 9001 (Jumbo Frames).
* **Warm-up Protocol:** Every scenario begins with a gradual 5–10 minute ramp to pre-warm JVM JIT compilation, database shared buffers, and Redis caches before measurement begins.
