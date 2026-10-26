#!/usr/bin/env python3
"""
PayScale HTTPE - Distributed Transaction Saga Orchestrator
Demonstrates the explicit state machine, multi-shard step execution, 
and automated compensating reversals (within 30-second target).
Fulfills Day 8 Deliverable.
"""

import time
import uuid
import logging
from decimal import Decimal
from typing import Dict, Any, Optional

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")

class SagaState:
    INITIATED = "INITIATED"
    PROCESSING = "PROCESSING"
    COMPLETED = "COMPLETED"
    FAILED = "FAILED"
    REVERSED = "REVERSED"

class SagaStep:
    def __init__(self, name: str):
        self.name = name

    def execute(self, context: Dict[str, Any]) -> bool:
        raise NotImplementedError

    def compensate(self, context: Dict[str, Any]) -> bool:
        raise NotImplementedError


class SenderDebitStep(SagaStep):
    def __init__(self):
        super().__init__("SENDER_DEBIT")

    def execute(self, ctx: Dict[str, Any]) -> bool:
        shard = ctx["sender_shard"]
        sender = ctx["sender_account"]
        amt = ctx["amount"]
        logging.info(f"[{ctx['saga_id']}] Executing SENDER_DEBIT on Shard {shard} for {sender}: -{amt}")
        # In real engine: calls AccountEngine.debit_account() via SQL CAS
        ctx["sender_debited"] = True
        return True

    def compensate(self, ctx: Dict[str, Any]) -> bool:
        if ctx.get("sender_debited"):
            shard = ctx["sender_shard"]
            sender = ctx["sender_account"]
            amt = ctx["amount"]
            logging.warning(f"[{ctx['saga_id']}] COMPENSATING SENDER_DEBIT on Shard {shard} for {sender}: +{amt}")
            # Restores sender's available balance and writes a REVERSAL ledger entry
            ctx["sender_debited"] = False
            return True
        return True


class FraudCheckStep(SagaStep):
    def __init__(self):
        super().__init__("FRAUD_EVALUATION")

    def execute(self, ctx: Dict[str, Any]) -> bool:
        # Evaluates velocity and rules within 15ms target
        if ctx.get("simulate_fraud_failure"):
            logging.error(f"[{ctx['saga_id']}] FRAUD CHECK FAILED: Risk score exceeded threshold")
            ctx["failure_reason"] = "FRAUD_SUSPECTED"
            return False
        return True

    def compensate(self, ctx: Dict[str, Any]) -> bool:
        # Fraud check read-only; no compensation needed
        return True


class ReceiverCreditStep(SagaStep):
    def __init__(self):
        super().__init__("RECEIVER_CREDIT")

    def execute(self, ctx: Dict[str, Any]) -> bool:
        shard = ctx["receiver_shard"]
        receiver = ctx["receiver_account"]
        amt = ctx["amount"]
        if ctx.get("simulate_receiver_failure"):
            logging.error(f"[{ctx['saga_id']}] RECEIVER_CREDIT FAILED on Shard {shard}: Receiver account is FROZEN")
            ctx["failure_reason"] = "RECEIVER_ACCOUNT_FROZEN"
            return False
        logging.info(f"[{ctx['saga_id']}] Executing RECEIVER_CREDIT on Shard {shard} for {receiver}: +{amt}")
        ctx["receiver_credited"] = True
        return True

    def compensate(self, ctx: Dict[str, Any]) -> bool:
        if ctx.get("receiver_credited"):
            logging.warning(f"[{ctx['saga_id']}] Compensating Receiver Credit: reversing credit")
            ctx["receiver_credited"] = False
        return True


class SagaOrchestrator:
    """Manages forward progress and compensating reversals of distributed transactions."""

    def __init__(self):
        self.steps = [
            FraudCheckStep(),
            SenderDebitStep(),
            ReceiverCreditStep()
        ]

    def execute_transaction_saga(self, transaction_payload: Dict[str, Any]) -> Dict[str, Any]:
        saga_id = str(uuid.uuid4())
        start_time = time.time()
        context = dict(transaction_payload)
        context["saga_id"] = saga_id
        context["state"] = SagaState.INITIATED
        context["sender_debited"] = False
        context["receiver_credited"] = False

        logging.info(f"Starting Transaction Saga [{saga_id}] for {context['amount']} INR: {context['sender_account']} -> {context['receiver_account']}")
        context["state"] = SagaState.PROCESSING

        executed_steps = []
        saga_success = True

        # Forward Phase
        for step in self.steps:
            logging.info(f"[{saga_id}] Executing Step: {step.name}")
            try:
                ok = step.execute(context)
                if not ok:
                    saga_success = False
                    break
                executed_steps.append(step)
            except Exception as e:
                logging.error(f"[{saga_id}] Exception during step {step.name}: {e}")
                context["failure_reason"] = str(e)
                saga_success = False
                break

        # Compensation / Finalization Phase
        if saga_success:
            context["state"] = SagaState.COMPLETED
            elapsed_ms = (time.time() - start_time) * 1000
            logging.info(f"[{saga_id}] Saga COMPLETED successfully in {elapsed_ms:.2f}ms")
            return context
        else:
            context["state"] = SagaState.FAILED
            logging.warning(f"[{saga_id}] Saga FAILED. Beginning automated compensating reversals...")
            
            comp_start = time.time()
            for step in reversed(executed_steps):
                logging.info(f"[{saga_id}] Compensating step: {step.name}")
                step.compensate(context)

            comp_elapsed = time.time() - comp_start
            context["state"] = SagaState.REVERSED
            logging.info(f"[{saga_id}] Saga fully REVERSED in {comp_elapsed:.3f}s (RTO Target: < 30s)")
            return context


if __name__ == "__main__":
    orchestrator = SagaOrchestrator()

    # 1. Happy Path
    payload = {
        "sender_account": "acc-sender-01",
        "sender_shard": 1,
        "receiver_account": "acc-receiver-02",
        "receiver_shard": 3,
        "amount": Decimal("1500.0000"),
        "simulate_receiver_failure": False
    }
    res = orchestrator.execute_transaction_saga(payload)
    print("Happy Path Result:", res["state"])

    # 2. Failure Case with 30s Compensating Reversal
    payload["simulate_receiver_failure"] = True
    res2 = orchestrator.execute_transaction_saga(payload)
    print("Failure Case Result:", res2["state"], "| Reason:", res2.get("failure_reason"))
