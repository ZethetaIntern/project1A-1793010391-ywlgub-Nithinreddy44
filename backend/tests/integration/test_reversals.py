import pytest
from httpx import AsyncClient
from decimal import Decimal

@pytest.mark.asyncio
async def test_transaction_reversal(client: AsyncClient):
    """
    Mandatory Test Scenario 11: Transaction Reversal
    - Execute P2P transfer
    - Request reversal
    - Verify reversal transaction is COMPLETED
    - Verify balances are restored to original amounts
    - Verify ledger double-entry holds balanced
    """
    resp_a = await client.post("/api/v1/accounts", json={
        "user_id": "user-rev-alice",
        "account_number": "ACC-REV-001",
        "account_type": "USER",
        "initial_balance": "3000.0000"
    })
    acc_a_id = resp_a.json()["account_id"]

    resp_b = await client.post("/api/v1/accounts", json={
        "user_id": "user-rev-bob",
        "account_number": "ACC-REV-002",
        "account_type": "USER",
        "initial_balance": "500.0000"
    })
    acc_b_id = resp_b.json()["account_id"]

    # 1. Transfer 1000 INR
    tx_resp = await client.post("/api/v1/payments/p2p", json={
        "idempotency_key": "tx-to-reverse-01",
        "source_account_id": acc_a_id,
        "destination_account_id": acc_b_id,
        "amount": "1000.0000"
    })
    assert tx_resp.status_code == 201
    tx_id = tx_resp.json()["transaction_id"]

    # 2. Reversal request
    rev_resp = await client.post(f"/api/v1/transactions/{tx_id}/reversals", json={
        "idempotency_key": "rev-req-key-01",
        "reason": "Customer dispute resolution"
    })
    assert rev_resp.status_code == 200
    rev_data = rev_resp.json()
    assert rev_data["status"] == "COMPLETED"
    assert rev_data["transaction_type"] == "REVERSAL"

    # 3. Verify Balances Restored
    alice_check = await client.get(f"/api/v1/accounts/{acc_a_id}")
    assert Decimal(str(alice_check.json()["available_balance"])) == Decimal("3000.0000")

    bob_check = await client.get(f"/api/v1/accounts/{acc_b_id}")
    assert Decimal(str(bob_check.json()["available_balance"])) == Decimal("500.0000")

    # 4. Verify Ledger Reconciliation
    recon_resp = await client.get("/reconciliation/verify")
    assert recon_resp.json()["is_balanced"] is True
