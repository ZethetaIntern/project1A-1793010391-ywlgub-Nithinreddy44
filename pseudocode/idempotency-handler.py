#!/usr/bin/env python3
"""
PayScale HTTPE - Distributed Idempotency Handler
Atomic key reservation in Redis, 24-hour retention window, 
payload SHA-256 fingerprint validation, and conflict resolution.
Fulfills Day 9 Deliverable.
"""

import time
import json
import hashlib
import logging
from typing import Dict, Any, Tuple, Optional

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")

class IdempotencyConflictException(Exception):
    """Raised when an existing key is presented with a mismatched payload."""
    pass

class TransactionInProgressException(Exception):
    """Raised when a concurrent request with the same key is already processing."""
    pass

class MockRedisCluster:
    """Simulates Redis Cluster with atomic SET NX PX and hash slot management."""
    def __init__(self):
        self._store: Dict[str, Dict[str, Any]] = {}

    def set_nx_ex(self, key: str, value: Dict[str, Any], ttl_seconds: int = 86400) -> bool:
        now = time.time()
        # Clean expired
        if key in self._store and self._store[key]["expires_at"] <= now:
            del self._store[key]

        if key in self._store:
            return False  # Key already exists
        
        self._store[key] = {
            "data": value,
            "expires_at": now + ttl_seconds
        }
        return True

    def get(self, key: str) -> Optional[Dict[str, Any]]:
        now = time.time()
        if key in self._store:
            if self._store[key]["expires_at"] <= now:
                del self._store[key]
                return None
            return self._store[key]["data"]
        return None

    def update(self, key: str, value: Dict[str, Any]):
        if key in self._store:
            self._store[key]["data"].update(value)


class IdempotencyHandler:
    def __init__(self, redis: MockRedisCluster, retention_seconds: int = 86400):
        self.redis = redis
        self.retention_seconds = retention_seconds  # Mandatory 24-hour window per spec

    def _hash_payload(self, payload: Dict[str, Any]) -> str:
        """Deterministic canonical JSON serialization and SHA-256 hash."""
        canonical_json = json.dumps(payload, sort_keys=True, separators=(',', ':'))
        return hashlib.sha256(canonical_json.encode('utf-8')).hexdigest()

    def process_with_idempotency(
        self,
        idempotency_key: str,
        request_payload: Dict[str, Any],
        business_logic_executor
    ) -> Tuple[int, Dict[str, Any]]:
        """
        Coordinates idempotent execution:
        1. Checks if key exists.
        2. Validates payload fingerprint match.
        3. Executes business logic if new key.
        4. Returns cached response if duplicate.
        """
        redis_key = f"idempotency:{idempotency_key}"
        payload_hash = self._hash_payload(request_payload)

        # Step 1: Check existing record
        existing = self.redis.get(redis_key)
        if existing:
            # Step 1a: Validate payload fingerprint
            if existing["payload_hash"] != payload_hash:
                logging.error(f"Idempotency conflict for key {idempotency_key}: payload hash mismatch!")
                raise IdempotencyConflictException(
                    "Idempotency key already exists with a different request payload. HTTP 409 Conflict."
                )

            # Step 1b: Check status
            if existing["status"] == "IN_FLIGHT":
                logging.warning(f"Concurrent request in-flight for idempotency key {idempotency_key}")
                raise TransactionInProgressException(
                    "A transaction with this idempotency key is currently processing. Please retry shortly."
                )
            
            # Step 1c: Return cached response (Zero duplicate financial side-effects)
            logging.info(f"Idempotency Cache HIT for {idempotency_key}: returning cached response.")
            return existing["status_code"], existing["response_body"]

        # Step 2: Atomic Reservation (SET NX EX)
        initial_record = {
            "payload_hash": payload_hash,
            "status": "IN_FLIGHT",
            "created_at": time.time()
        }
        reserved = self.redis.set_nx_ex(redis_key, initial_record, self.retention_seconds)
        if not reserved:
            # Race condition: another thread reserved it between our GET and SETNX
            return self.process_with_idempotency(idempotency_key, request_payload, business_logic_executor)

        logging.info(f"Reserved new idempotency key: {idempotency_key} (24-hour retention)")

        # Step 3: Execute Business Logic
        try:
            status_code, response_body = business_logic_executor(request_payload)
            # Step 4: Cache terminal response
            self.redis.update(redis_key, {
                "status": "COMPLETED",
                "status_code": status_code,
                "response_body": response_body,
                "completed_at": time.time()
            })
            return status_code, response_body
        except Exception as e:
            # Mark record as FAILED so client can retry with same key
            self.redis.update(redis_key, {
                "status": "FAILED",
                "error": str(e)
            })
            raise e


if __name__ == "__main__":
    redis = MockRedisCluster()
    handler = IdempotencyHandler(redis)

    execution_count = 0
    def payment_business_logic(payload):
        global execution_count
        execution_count += 1
        return 200, {
            "transaction_id": "018d9620-1a1a-7000-8000-000000000001",
            "status": "COMPLETED",
            "amount": payload["amount"],
            "execution_count": execution_count
        }

    key = "idem-tx-994821"
    payload_a = {"sender": "acc-1", "receiver": "acc-2", "amount": 1000.0}

    # 1. First execution
    code1, resp1 = handler.process_with_idempotency(key, payload_a, payment_business_logic)
    print("1st Call (Execution):", resp1)

    # 2. Repeated execution with identical payload (Should return cached result, execution_count unchanged)
    code2, resp2 = handler.process_with_idempotency(key, payload_a, payment_business_logic)
    print("2nd Call (Cached):", resp2)
    assert resp1 == resp2
    assert execution_count == 1, "Financial logic must NOT execute twice!"

    # 3. Execution with conflicting payload under same key -> Should raise Conflict Exception
    payload_conflict = {"sender": "acc-1", "receiver": "acc-2", "amount": 9999.0}
    try:
        handler.process_with_idempotency(key, payload_conflict, payment_business_logic)
    except IdempotencyConflictException as e:
        print("3rd Call (Conflict Caught):", e)
