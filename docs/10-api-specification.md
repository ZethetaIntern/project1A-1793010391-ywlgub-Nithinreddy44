# API Specification, Security & Integration Standards: PayScale HTTPE

## 1. REST Architectural Standards & Guarantees

The PayScale High-Throughput Transaction Processing Engine (HTTPE) exposes a standardized, versioned RESTful HTTP/2 API. All endpoints enforce JSON payloads over TLS 1.3, input validation, deterministic idempotency, and explicit error schemas.

### 1.1 Invariant API Rules
* **No Secret Leakage:** Passwords, private keys, database connection strings, and internal stack traces are strictly forbidden in API error responses.
* **Sensitive Data Masking:** PAN numbers are hashed (SHA-256), Aadhaar references are tokenized, and account numbers are masked (`XXXX-XXXX-1234`) in external responses.
* **Correlation ID Propagation:** Every request requires (or is assigned by the API Gateway) an `X-Correlation-ID` header (UUIDv7 format). This correlation ID is injected into MDC logging context, database query comments, and Kafka record headers for end-to-end distributed tracing.

---

## 2. Cursor-Based Pagination Design

To support low-latency transaction history queries across accounts with millions of records, standard offset-based pagination (`OFFSET 100000 LIMIT 20`) is strictly prohibited due to its $O(N)$ full-table-scan degradation.

PayScale enforces **Opaque Cursor-Based Pagination**:
* **Query Parameter:** `GET /api/v1/accounts/{accountId}/transactions?cursor={opaque_cursor}&limit=20`
* **Underlying Query Execution:**
  ```sql
  SELECT transaction_id, created_at, amount, status, ...
  FROM transactions
  WHERE (source_account_id = :accountId OR destination_account_id = :accountId)
    AND (created_at, transaction_id) < (:cursorCreatedAt, :cursorTransactionId)
  ORDER BY created_at DESC, transaction_id DESC
  LIMIT :limit;
  ```
* **Opaque Cursor Format:** A Base64-encoded JSON string containing the chronological boundary tuple:
  $$\text{cursor} = \text{Base64}\left(\text{JSON}\left(\{\text{"created\_at"}: \text{"2026-10-26T08:30:00Z"}, \text{"id"}: \text{"018d9620-1a1a-7000-8000-000000000001"}\}\right)\right)$$
* **Index Utilization:** Leverages the composite index `idx_txns_source_created (source_account_id, created_at DESC)` for instantaneous $O(\log N)$ seeks regardless of whether the account has 10 or 10,000,000 transactions.

---

## 3. Tiered Token-Bucket Rate Limiting

To prevent the false-positive 429 rejections observed in legacy testing (Bottleneck BN-005), rate limiting is enforced per authenticated account tier rather than per shared NAT IP gateway.

Rate limits are evaluated atomically in Redis Cluster via an optimized Lua token-bucket script:

| Account Tier | Sustained Rate | Burst Capacity | Token Bucket Window | Primary Target Persona |
| :--- | :---: | :---: | :---: | :--- |
| **BASIC** | 20 TPS | 50 TPS | 1 second | Standard retail P2P customers |
| **PREMIUM** | 100 TPS | 200 TPS | 1 second | High-net-worth individuals, SMB traders |
| **MERCHANT** | 500 TPS | 1,000 TPS | 1 second | Large e-commerce partners & aggregators |
| **INTERNAL** | 5,000 TPS | 10,000 TPS | 1 second | Internal batch settlement and reconciliation services |

* **Headers Returned:**
  * `X-RateLimit-Limit`: Maximum permitted burst capacity.
  * `X-RateLimit-Remaining`: Tokens currently remaining in bucket.
  * `Retry-After`: Seconds to wait before submitting requests if rate limited (HTTP 429).

---

## 4. Standardized Error Response Catalog

All error responses conform to a uniform schema:
```json
{
  "error_code": "INSUFFICIENT_FUNDS",
  "message": "Available balance of 450.00 INR is insufficient for requested debit of 1500.00 INR.",
  "timestamp": "2026-10-26T08:30:15.120Z",
  "correlation_id": "018d9620-7f91-7000-8000-000000008812"
}
```

### Complete Error Code to HTTP Status Code Mapping:

| Error Code | HTTP Status | Description & Remediation |
| :--- | :---: | :--- |
| `INVALID_PAYLOAD` | 400 Bad Request | Malformed JSON, missing required fields, or schema validation failure |
| `INVALID_UUID` | 400 Bad Request | Supplied account ID or transaction ID is not a valid UUIDv7 |
| `UNAUTHORIZED` | 401 Unauthorized | Missing or expired JWT authentication token |
| `FORBIDDEN_FRAUD` | 403 Forbidden | Blocked by Fraud Detection Service due to high risk score or velocity breach |
| `ACCOUNT_NOT_FOUND` | 404 Not Found | Specified `account_id` does not exist on any active database shard |
| `TRANSACTION_NOT_FOUND` | 404 Not Found | Specified `transaction_id` does not exist |
| `IDEMPOTENCY_CONFLICT` | 409 Conflict | Existing idempotency key presented with a different payload fingerprint |
| `TRANSACTION_IN_PROGRESS`| 409 Conflict | A request with this idempotency key is currently being processed |
| `INSUFFICIENT_FUNDS` | 422 Unprocessable | Sender available balance is less than transfer amount |
| `ACCOUNT_FROZEN` | 422 Unprocessable | Sender or receiver account is frozen by compliance or administrative order |
| `RATE_LIMIT_EXCEEDED` | 429 Too Many Requests | Account has exceeded its tier TPS allotment; client must back off |
| `SERVICE_DEGRADED` | 503 Service Unavailable | Downstream circuit breaker is OPEN; fast-failed with `Retry-After` |
