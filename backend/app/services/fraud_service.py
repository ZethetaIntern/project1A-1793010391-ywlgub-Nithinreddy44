import time
from decimal import Decimal
from typing import Tuple

class FraudService:
    def __init__(self):
        # Maximum allowed single transaction for standard tier
        self.max_single_tx_amount = Decimal("100000.0000")
        # Velocity tracker: {account_id: [(timestamp, amount), ...]}
        self.velocity_history = {}

    async def evaluate_transaction(self, account_id: str, amount: Decimal) -> Tuple[bool, str, float]:
        """
        Evaluates transaction against fraud rules.
        Target SLA: <= 15 ms.
        Returns: (is_approved, reason, elapsed_ms)
        """
        start_time = time.perf_counter()
        
        # Rule 1: High single transaction threshold check
        if amount > self.max_single_tx_amount:
            elapsed = (time.perf_counter() - start_time) * 1000.0
            return False, "EXCEEDS_SINGLE_TRANSACTION_LIMIT", elapsed

        # Rule 2: Velocity check (no more than 10 transactions in 60 seconds)
        now = time.time()
        history = self.velocity_history.setdefault(account_id, [])
        # Prune older than 60s
        self.velocity_history[account_id] = [tx for tx in history if now - tx[0] < 60]
        
        if len(self.velocity_history[account_id]) >= 10:
            elapsed = (time.perf_counter() - start_time) * 1000.0
            return False, "VELOCITY_LIMIT_EXCEEDED", elapsed

        # Record this transaction attempt
        self.velocity_history[account_id].append((now, amount))
        
        elapsed = (time.perf_counter() - start_time) * 1000.0
        return True, "APPROVED", elapsed

fraud_service = FraudService()
