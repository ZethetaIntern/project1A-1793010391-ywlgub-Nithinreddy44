import pytest
from decimal import Decimal
from httpx import AsyncClient

@pytest.mark.asyncio
async def test_idempotency_duplicate_and_conflict(client: AsyncClient):
    """
    Mandatory Test Scenarios 4 & 5:
    - Same Key + Same Payload -> Exactly same response returned (HTTP 201) without double debit
    - Same Key + Conflicting Payload -> HTTP 409 Conflict rejection
    """
    # Create Accounts
    resp_a = await client.post("/api/v1/accounts", json={
        "user_id": "user-idem-alice",
        "account_number": "ACC-IDEM-001",
        "account_type": "USER",
        "initial_balance": "10000.0000"
    })
    acc_a_id = resp_a.json()["account_id"]

    resp_b = await client.post("/api/v1/accounts", json={
        "user_id": "user-idem-bob",
        "account_number": "ACC-IDEM-002",
        "account_type": "USER",
        "initial_balance": "0.0000"
    })
    acc_b_id = resp_b.json()["account_id"]

    idempotency_key = "unique-key-xyz-789"

    # Request 1: Initial submission
    req_payload = {
        "idempotency_key": idempotency_key,
        "source_account_id": acc_a_id,
        "destination_account_id": acc_b_id,
        "amount": "1000.0000",
        "currency": "INR"
    }
    resp1 = await client.post("/api/v1/payments/p2p", json=req_payload)
    assert resp1.status_code == 201
    tx_id_1 = resp1.json()["transaction_id"]

    # Request 2: Duplicate key, EXACT same payload -> Must return cached/stored result
    resp2 = await client.post("/api/v1/payments/p2p", json=req_payload)
    assert resp2.status_code == 201
    assert resp2.json()["transaction_id"] == tx_id_1

    # Verify Alice balance only debited ONCE (10000 - 1000 = 9000, NOT 8000)
    acc_check = await client.get(f"/api/v1/accounts/{acc_a_id}")
    assert Decimal(str(acc_check.json()["available_balance"])) == Decimal("9000.0000")

    # Request 3: Same key, CONFLICTING payload (amount changed to 2000) -> HTTP 409 Conflict
    conflict_payload = {
        "idempotency_key": idempotency_key,
        "source_account_id": acc_a_id,
        "destination_account_id": acc_b_id,
        "amount": "2000.0000",
        "currency": "INR"
    }
    resp3 = await client.post("/api/v1/payments/p2p", json=conflict_payload)
    assert resp3.status_code == 409
    assert "conflicting payload" in resp3.json()["detail"].lower()
