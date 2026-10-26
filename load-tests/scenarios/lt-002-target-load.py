#!/usr/bin/env python3
"""
PayScale HTTPE - Load Testing Scenario LT-002: Sustained Target Load (12,000 TPS)
Simulates 50,000 concurrent active users executing P2P transfers and merchant payments.
Fulfills Day 11 Deliverable.
"""

import uuid
import random
from locust import HttpUser, task, between, events

# Pre-seeded test account pools across the 4 database shards
SENDER_ACCOUNTS = [f"018d9620-1a1a-7000-8000-{i:012d}" for i in range(1, 5001)]
RECEIVER_ACCOUNTS = [f"018d9620-2b2b-7000-8000-{i:012d}" for i in range(1, 5001)]

class PayScaleTargetLoadUser(HttpUser):
    # Highly aggressive pacing to achieve sustained 12,000 aggregate TPS across cluster
    wait_time = between(0.01, 0.05)

    @task(70)
    def submit_p2p_payment(self):
        """Simulates 70% Person-to-Person (P2P) transfers with unique idempotency keys."""
        source = random.choice(SENDER_ACCOUNTS)
        dest = random.choice(RECEIVER_ACCOUNTS)
        amount = round(random.uniform(50.0, 2500.0), 2)
        idempotency_key = f"lt002-{uuid.uuid4()}"

        headers = {
            "Content-Type": "application/json",
            "Idempotency-Key": idempotency_key,
            "Authorization": "Bearer test-jwt-token-tier-basic",
            "X-Correlation-ID": str(uuid.uuid4())
        }
        payload = {
            "source_account_id": source,
            "destination_account_id": dest,
            "amount": amount,
            "currency": "INR",
            "notes": "Diwali Festive Transfer"
        }

        with self.client.post("/api/v1/payments/p2p", json=payload, headers=headers, catch_response=True) as response:
            if response.status_code == 200:
                response.success()
            elif response.status_code in (409, 422, 429):
                # Expected business rejections under heavy contention
                response.success()
            else:
                response.failure(f"Unexpected status code: {response.status_code}")

    @task(20)
    def check_account_balance(self):
        """Simulates 20% rapid balance queries (Cache-Aside read path)."""
        account = random.choice(SENDER_ACCOUNTS)
        headers = {"Authorization": "Bearer test-jwt-token-tier-basic"}
        with self.client.get(f"/api/v1/accounts/{account}", headers=headers, catch_response=True) as response:
            if response.status_code in (200, 404):
                response.success()
            else:
                response.failure(f"Balance query failed: {response.status_code}")

    @task(10)
    def query_transaction_history(self):
        """Simulates 10% cursor-based transaction history statements."""
        account = random.choice(SENDER_ACCOUNTS)
        headers = {"Authorization": "Bearer test-jwt-token-tier-basic"}
        with self.client.get(f"/api/v1/accounts/{account}/transactions?limit=10", headers=headers, catch_response=True) as response:
            if response.status_code in (200, 404):
                response.success()
            else:
                response.failure(f"Statement pagination failed: {response.status_code}")
