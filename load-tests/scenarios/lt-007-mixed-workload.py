#!/usr/bin/env python3
"""
PayScale HTTPE - Load Testing Scenario LT-007: Realistic Mixed Workload
Simulates production ratio: 60% P2P, 20% Balance Checks, 10% Merchant Payments,
5% Reversals, and 5% Account/Statement operations.
Fulfills Day 11 Deliverable.
"""

import uuid
import random
from locust import HttpUser, task, between

ACCOUNTS = [f"018d9620-1a1a-7000-8000-{i:012d}" for i in range(1, 3001)]
MERCHANTS = [f"018d9620-9999-7000-8000-{i:012d}" for i in range(1, 51)]

class MixedWorkloadUser(HttpUser):
    wait_time = between(0.01, 0.05)

    @task(60)
    def p2p_transfer(self):
        source = random.choice(ACCOUNTS)
        dest = random.choice(ACCOUNTS)
        if source == dest:
            return

        payload = {
            "source_account_id": source,
            "destination_account_id": dest,
            "amount": round(random.uniform(20.0, 1500.0), 2),
            "currency": "INR"
        }
        headers = {
            "Content-Type": "application/json",
            "Idempotency-Key": f"mix-{uuid.uuid4()}",
            "Authorization": "Bearer test-jwt-token"
        }
        self.client.post("/api/v1/payments/p2p", json=payload, headers=headers)

    @task(20)
    def balance_inquiry(self):
        account = random.choice(ACCOUNTS)
        self.client.get(f"/api/v1/accounts/{account}", headers={"Authorization": "Bearer test-jwt-token"})

    @task(10)
    def merchant_checkout(self):
        customer = random.choice(ACCOUNTS)
        merchant = random.choice(MERCHANTS)
        payload = {
            "customer_account_id": customer,
            "merchant_account_id": merchant,
            "amount": round(random.uniform(100.0, 4999.0), 2),
            "currency": "INR",
            "order_id": f"ORD-{uuid.uuid4()}"
        }
        headers = {
            "Content-Type": "application/json",
            "Idempotency-Key": f"mkt-{uuid.uuid4()}",
            "Authorization": "Bearer test-jwt-token"
        }
        self.client.post("/api/v1/payments/merchant", json=payload, headers=headers)

    @task(5)
    def statement_history(self):
        account = random.choice(ACCOUNTS)
        self.client.get(f"/api/v1/accounts/{account}/transactions?limit=15", headers={"Authorization": "Bearer test-jwt-token"})

    @task(5)
    def request_reversal(self):
        # Trigger reversal for a recent transaction
        txn_id = str(uuid.uuid4())
        headers = {
            "Content-Type": "application/json",
            "Idempotency-Key": f"rev-{uuid.uuid4()}",
            "Authorization": "Bearer test-jwt-token"
        }
        self.client.post(f"/api/v1/transactions/{txn_id}/reversals", json={"reason": "CUSTOMER_DISPUTE"}, headers=headers)
