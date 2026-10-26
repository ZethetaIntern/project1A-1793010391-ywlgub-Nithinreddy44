import pytest
from httpx import AsyncClient
from decimal import Decimal

@pytest.mark.asyncio
async def test_health_check(client: AsyncClient):
    response = await client.get("/health")
    assert response.status_code == 200
    data = response.json()
    assert data["status"] == "UP"
    assert "version" in data

@pytest.mark.asyncio
async def test_successful_p2p_transfer_and_ledger(client: AsyncClient):
    """
    Mandatory Test Scenario 1:
    - Create Account A with 5000 INR
    - Create Account B with 1000 INR
    - Execute P2P transfer of 1500 INR
    - Verify:
      - Source balance = 3500 INR
      - Destination balance = 2500 INR
      - Status = COMPLETED
      - Ledger invariant holds (Debits == Credits)
    """
    # 1. Create Account A
    resp_a = await client.post("/api/v1/accounts", json={
        "user_id": "user-alice-001",
        "account_number": "ACC-ALICE-1001",
        "account_type": "USER",
        "initial_balance": "5000.0000"
    })
    assert resp_a.status_code == 201
    acc_a = resp_a.json()
    acc_a_id = acc_a["account_id"]

    # 2. Create Account B
    resp_b = await client.post("/api/v1/accounts", json={
        "user_id": "user-bob-002",
        "account_number": "ACC-BOB-2002",
        "account_type": "USER",
        "initial_balance": "1000.0000"
    })
    assert resp_b.status_code == 201
    acc_b = resp_b.json()
    acc_b_id = acc_b["account_id"]

    # 3. Transfer 1500 INR
    transfer_resp = await client.post("/api/v1/payments/p2p", json={
        "idempotency_key": "p2p-tx-alice-bob-001",
        "source_account_id": acc_a_id,
        "destination_account_id": acc_b_id,
        "amount": "1500.0000",
        "currency": "INR"
    })
    assert transfer_resp.status_code == 201
    tx_data = transfer_resp.json()
    assert tx_data["status"] == "COMPLETED"
    assert tx_data["amount"] == "1500.0000"

    # 4. Verify Alice Balance
    alice_check = await client.get(f"/api/v1/accounts/{acc_a_id}")
    assert alice_check.status_code == 200
    assert Decimal(str(alice_check.json()["available_balance"])) == Decimal("3500.0000")

    # 5. Verify Bob Balance
    bob_check = await client.get(f"/api/v1/accounts/{acc_b_id}")
    assert bob_check.status_code == 200
    assert Decimal(str(bob_check.json()["available_balance"])) == Decimal("2500.0000")

    # 6. Verify Reconciliation
    recon_resp = await client.get("/reconciliation/verify")
    assert recon_resp.status_code == 200
    recon = recon_resp.json()
    assert recon["is_balanced"] is True
    assert Decimal(str(recon["discrepancy"])) == Decimal("0.0000")

@pytest.mark.asyncio
async def test_insufficient_funds_rejection(client: AsyncClient):
    """
    Mandatory Test Scenario 2:
    - Attempt transfer of 9999 INR from Account with 100 INR balance
    - Must fail with HTTP 422 Unprocessable Entity
    - Balance must remain unchanged
    """
    resp_a = await client.post("/api/v1/accounts", json={
        "user_id": "user-low-funds",
        "account_number": "ACC-LOW-001",
        "account_type": "USER",
        "initial_balance": "100.0000"
    })
    acc_a_id = resp_a.json()["account_id"]

    resp_b = await client.post("/api/v1/accounts", json={
        "user_id": "user-dest-funds",
        "account_number": "ACC-DEST-002",
        "account_type": "USER",
        "initial_balance": "50.0000"
    })
    acc_b_id = resp_b.json()["account_id"]

    transfer_resp = await client.post("/api/v1/payments/p2p", json={
        "idempotency_key": "insufficient-funds-key-01",
        "source_account_id": acc_a_id,
        "destination_account_id": acc_b_id,
        "amount": "9999.0000"
    })
    assert transfer_resp.status_code == 422
    assert "Insufficient available funds" in transfer_resp.json()["detail"]

    # Verify Alice balance intact
    alice_check = await client.get(f"/api/v1/accounts/{acc_a_id}")
    assert Decimal(str(alice_check.json()["available_balance"])) == Decimal("100.0000")
