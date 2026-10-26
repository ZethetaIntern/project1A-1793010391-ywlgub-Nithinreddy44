#!/usr/bin/env python3
"""
PayScale HTTPE - Optimistic Concurrency Control (OCC) Balance Update Mechanism
Demonstrates atomic Compare-And-Swap (CAS) with adaptive backoff retry loop.
Fulfills Day 8 Deliverable for high-throughput balance management.
"""

import time
import random
import logging
from decimal import Decimal
from typing import Optional, Tuple, Dict, Any

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")

class InsufficientBalanceException(Exception):
    pass

class OptimisticLockException(Exception):
    pass

class AccountRepository:
    """Simulates PostgreSQL Shard Database Operations with OCC predicates."""
    
    def __init__(self):
        # Simulated database table: account_id -> {balance, version, status}
        self._db: Dict[str, Dict[str, Any]] = {}

    def seed_account(self, account_id: str, balance: Decimal, version: int = 1, status: str = "ACTIVE"):
        self._db[account_id] = {
            "account_id": account_id,
            "available_balance": balance,
            "ledger_balance": balance,
            "version": version,
            "status": status,
            "updated_at": time.time()
        }

    def find_account_for_read(self, account_id: str) -> Optional[Dict[str, Any]]:
        """Non-locking read of account row (MVCC snapshot)."""
        row = self._db.get(account_id)
        if not row:
            return None
        # Return a copy to simulate reading a database snapshot
        return dict(row)

    def execute_atomic_cas_debit(self, account_id: str, amount: Decimal, expected_version: int) -> bool:
        """
        Executes the equivalent of:
        UPDATE accounts
        SET available_balance = available_balance - :amount,
            version = version + 1,
            updated_at = NOW()
        WHERE account_id = :accountId
          AND version = :expectedVersion
          AND available_balance >= :amount;
        """
        row = self._db.get(account_id)
        if not row:
            return False

        # In PostgreSQL, row evaluation happens under an internal tuple write lock
        current_version = row["version"]
        current_balance = row["available_balance"]

        # Atomic Compare-And-Swap Predicate
        if current_version == expected_version and current_balance >= amount:
            row["available_balance"] -= amount
            row["version"] += 1
            row["updated_at"] = time.time()
            return True  # 1 row updated
        
        return False  # 0 rows updated (version mismatch or balance dropped)

    def execute_atomic_credit(self, account_id: str, amount: Decimal) -> bool:
        """Credits can never fail due to balance insufficiency; atomic version increment."""
        row = self._db.get(account_id)
        if not row:
            return False
        row["available_balance"] += amount
        row["ledger_balance"] += amount
        row["version"] += 1
        row["updated_at"] = time.time()
        return True


class OCCBalanceEngine:
    """Core balance management engine implementing OCC and exponential backoff retries."""

    def __init__(self, repo: AccountRepository, max_retries: int = 3, base_backoff_ms: float = 2.0, max_backoff_ms: float = 15.0):
        self.repo = repo
        self.max_retries = max_retries
        self.base_backoff_ms = base_backoff_ms
        self.max_backoff_ms = max_backoff_ms

    def debit_account(self, account_id: str, amount: Decimal) -> Tuple[bool, Decimal, int]:
        """
        Debits an account using non-blocking OCC with bounded adaptive retries.
        Returns: (success, final_balance, final_version)
        """
        if amount <= Decimal("0.0000"):
            raise ValueError("Debit amount must be strictly positive")

        attempts = 0
        while attempts < self.max_retries:
            attempts += 1
            
            # Step 1: Non-locking read of current state
            account = self.repo.find_account_for_read(account_id)
            if not account:
                raise ValueError(f"Account {account_id} not found")
            
            if account["status"] != "ACTIVE":
                raise ValueError(f"Account {account_id} is in non-active status: {account['status']}")

            current_balance = account["available_balance"]
            expected_version = account["version"]

            # Step 2: In-memory sufficiency validation
            if current_balance < amount:
                logging.warning(f"Debit rejected: account {account_id} has {current_balance} < {amount}")
                raise InsufficientBalanceException("Insufficient available balance for debit")

            # Step 3: Atomic CAS execution
            success = self.repo.execute_atomic_cas_debit(
                account_id=account_id,
                amount=amount,
                expected_version=expected_version
            )

            if success:
                new_balance = current_balance - amount
                new_version = expected_version + 1
                logging.info(f"OCC Debit Success on attempt {attempts}: Account {account_id}, -{amount}, NewBal={new_balance}, Ver={new_version}")
                return True, new_balance, new_version

            # Step 4: Collision detected (0 rows updated) -> Exponential backoff with full jitter
            backoff = min(self.max_backoff_ms, self.base_backoff_ms * (2 ** (attempts - 1)))
            jittered_sleep = (backoff + random.uniform(0, 3.0)) / 1000.0
            logging.debug(f"OCC Collision for {account_id} (Attempt {attempts}/{self.max_retries}). Retrying in {jittered_sleep*1000:.2f}ms...")
            time.sleep(jittered_sleep)

        # Retries exhausted
        logging.error(f"OCC Retries Exhausted ({self.max_retries} attempts) for account {account_id}")
        raise OptimisticLockException(f"Failed to update balance due to heavy concurrent contention after {self.max_retries} attempts")


if __name__ == "__main__":
    repo = AccountRepository()
    engine = OCCBalanceEngine(repo)

    # Demo 1: Happy Path
    repo.seed_account("acc-001", Decimal("5000.0000"), version=1)
    success, bal, ver = engine.debit_account("acc-001", Decimal("1500.0000"))
    print(f"Result: Success={success}, Balance={bal}, Version={ver}")

    # Demo 2: Insufficient Funds
    try:
        engine.debit_account("acc-001", Decimal("10000.0000"))
    except InsufficientBalanceException as e:
        print(f"Caught expected error: {e}")
