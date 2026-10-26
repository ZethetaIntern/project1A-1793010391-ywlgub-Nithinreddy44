# End-to-End Latency Performance Budget: PayScale HTTPE

## 1. Objective & SLA Target

Under Section A4.2 (Non-Functional Requirements) of the HTTPE specification:
* **Target p50 Latency:** $\le 30\text{ ms}$
* **Target p99 Latency:** $\le 100\text{ ms}$ (Strict SLA ceiling)
* **Target p99.9 Latency:** $\le 250\text{ ms}$ (Edge case ceiling)

To ensure that the 100 ms p99 latency target is consistently satisfied across every request under peak 12,000 sustained and 18,000 burst TPS, the total available latency budget is mathematically decomposed across each processing stage along the critical transaction execution path.

---

## 2. Granular Stage-by-Stage Latency Budget Allocation

```
[ Client ] 
   │ (1. Network Transit: 8-15 ms)
   ▼
[ API Gateway ] 
   │ (2. SSL + Routing: 3-5 ms)
   │ (3. JWT & Rate Limit: 2-3 ms)
   ▼
[ Transaction Orchestrator ]
   │ (4. Idempotency Check in Redis: 1-2 ms)
   │ (5. Fraud Detection Service: 10-15 ms)
   │ (6. Business Logic Validation: 1-2 ms)
   ▼
[ Payment & Account Service ]
   │ (7. Database Read / Balance Pre-check: 3-5 ms)
   │ (8. Database Write / Atomic OCC CAS + Ledger: 8-15 ms)
   │ (9. Outbox Event Commit: Included in DB txn)
   ▼
[ Async Offload (Out of Critical Path) ]
   ├── Kafka Event Produce: 2-5 ms (Async)
   └── Response JSON Serialization: 2-3 ms
```

### Complete Budget Breakdown Table:

| Processing Stage | Component / Infrastructure | Budget Range (ms) | P99 Target (ms) | Architectural Optimization & Defense |
| :--- | :--- | :---: | :---: | :--- |
| **Stage 1: Network Ingress Transit** | AWS Direct Connect / CloudFront CDN | 8 – 15 ms | 15.0 ms | Edge termination in Mumbai PoP; TCP keep-alive |
| **Stage 2: SSL & Gateway Routing** | Envoy Proxy / Hardware SSL Offload | 3 – 5 ms | 4.0 ms | Hardware AES-NI crypto acceleration; in-memory C++ route table |
| **Stage 3: Auth & Rate Limiting** | Spring Security JWT + Redis Lua | 2 – 3 ms | 2.5 ms | Cached public keys in memory; local Lua token bucket execution |
| **Stage 4: Idempotency Verification** | Redis Cluster (`SET NX EX`) | 1 – 2 ms | 1.8 ms | Single round-trip over internal VPC link (< 0.5 ms network hop) |
| **Stage 5: Real-time Fraud Scoring** | Fraud Detection Service + Feature Store | 10 – 15 ms | 14.5 ms | In-memory feature vectors; ONNX inference (< 8 ms); 15 ms circuit breaker timeout |
| **Stage 6: Business Validation** | Application Memory (Java Virtual Threads) | 1 – 2 ms | 1.2 ms | Pure CPU in-memory state validation; zero blocking I/O |
| **Stage 7: DB Read (Balance Check)** | PostgreSQL Read Shard / Replica | 3 – 5 ms | 4.5 ms | B-tree index on `account_id`; shared buffers hit (> 95%) |
| **Stage 8: DB Write (OCC Debit + Journal)** | PostgreSQL Primary Shard via PgBouncer | 8 – 15 ms | 14.0 ms | Single atomic transaction; NVMe gp3 disk at 12,000 IOPS; group commit fsync |
| **Stage 9: Response Serialization** | Jackson JSON Serializer + HTTP/2 Stream | 2 – 3 ms | 2.5 ms | Zero-allocation byte buffers; HTTP/2 multiplexing |
| **Contingency / OCC Retry Headroom** | 1 Adaptive Backoff Collision Retry | 0 – 15 ms | 12.0 ms | Accommodates 1 OCC collision retry (2ms base + jitter) |
| **TOTAL CRITICAL PATH (Worst-Case)**| **End-to-End Cumulative** | **38 – 75 ms** | **72.0 ms** | **Comfortably under the 100 ms p99 ceiling (28 ms headroom)** |

---

## 3. Asynchronous Offload (Non-Blocking Paths)

The following heavy operations are strictly excluded from the synchronous client-blocking path:
* **Kafka Event Ingestion:** Outbox events are written to the local database table in Stage 8. Debezium CDC captures changes from the WAL asynchronously.
* **Notification Dispatch:** WebSocket and webhook delivery runs asynchronously with a target propagation of $< 500\text{ ms}$.
* **Ledger Reconciliation:** ClickHouse and DuckDB audits run asynchronously in background batches.
* **Deep ML Fraud Retraining:** Batch training pipelines execute out-of-band in AWS SageMaker.

---

## 4. Latency SLA Verification & Enforcement

* **Automated Latency Budget Breaker:** OpenTelemetry distributed spans measure latency across every individual stage.
* If any stage exceeds its allocated budget by > 50% for 3 consecutive minutes (e.g., Database write > 22.5 ms), Alertmanager fires a **P2 Latency Drift Alert**.
* If end-to-end p99 exceeds 90 ms (approaching the 100 ms SLA ceiling), the API Gateway initiates graceful load-shedding of non-critical background traffic.
