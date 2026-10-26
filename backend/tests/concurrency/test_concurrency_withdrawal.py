import pytest
import asyncio
from httpx import AsyncClient
from decimal import Decimal

@pytest.mark.asyncio
async def test_concurrent_withdrawals_no_double_spend(client: AsyncClient):
    """
    Mandatory Test Scenario 3: Concurrency Control
    - Account A has 1000 INR
    - Two concurrent transfers of 800 INR are dispatched simultaneously
    - Exactly ONE transfer can succeed (1000 - 800 = 200 INR left)
    - The other transfer MUST fail or reject (insufficient funds or OCC conflict)
    - Account balance MUST NEVER drop below zero
    """
    # Create Accounts
    resp_a = await client.post("/api/v1/accounts", json={
        "user_id": "user-concurrency-alice",
        "account_number": "ACC-CONCUR-001",
        "account_type": "USER",
        "initial_balance": "1000.0000"
    })
    acc_a_id = resp_a.json()["account_id"]

    resp_b = await client.post("/api/v1/accounts", json={
        "user_id": "user-concurrency-bob",
        "account_number": "ACC-CONCUR-002",
        "account_type": "USER",
        "initial_balance": "0.0000"
    })
    acc_b_id = resp_b.json()["account_id"]

    # Dispatch 2 simultaneous requests
    req1 = client.post("/api/v1/payments/p2p", json={
        "idempotency_key": "concurrent-tx-1",
        "source_account_id": acc_a_id,
        "destination_account_id": acc_b_id,
        "amount": "800.0000"
    })
    req2 = client.post("/api/v1/payments/p2p", json={
        "idempotency_key": "concurrent-tx-2",
        "source_account_id": acc_a_id,
        "destination_account_id": acc_b_id,
        "amount": "800.0000"
    })

    results = await asyncio.gather(req1, req2, return_exceptions=True)

    status_codes = [r.status_code for r in results if not isinstance(r, Exception)]
    
    # Exactly one request must succeed (HTTP 201)
    success_count = status_codes.count(201)
    assert success_count == 1, f"Expected exactly 1 success, got status codes: {status_codes}"

    # Verify final balance is exactly 200.0000 and never negative
    acc_check = await client.get(f"/api/v1/accounts/{acc_a_id}")
    final_balance = Decimal(str(acc_check.json()["available_balance"]))
    assert final_balance == Decimal("200.0000")
    assert final_balance >= Decimal("0.0000")
