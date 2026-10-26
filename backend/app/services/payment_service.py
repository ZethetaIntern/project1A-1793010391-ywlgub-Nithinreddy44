import asyncio
import datetime
import uuid
from decimal import Decimal
from typing import Dict, Any, Tuple
from fastapi import HTTPException, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.account import Account
from app.models.transaction import Transaction
from app.models.ledger import LedgerEntry
from app.models.outbox import OutboxEvent
from app.repositories.account_repo import AccountRepository
from app.repositories.transaction_repo import TransactionRepository, LedgerRepository
from app.services.idempotency_service import idempotency_service
from app.services.fraud_service import fraud_service

class PaymentService:
    def __init__(self, session: AsyncSession):
        self.session = session
        self.account_repo = AccountRepository(session)
        self.tx_repo = TransactionRepository(session)
        self.ledger_repo = LedgerRepository(session)

    async def execute_p2p_payment(
        self,
        idempotency_key: str,
        source_account_id: str,
        destination_account_id: str,
        amount: Decimal,
        currency: str = "INR",
        correlation_id: str = None
    ) -> Transaction:
        correlation_id = correlation_id or str(uuid.uuid4())
        payload_dict = {
            "source_account_id": source_account_id,
            "destination_account_id": destination_account_id,
            "amount": str(amount),
            "currency": currency
        }
        payload_hash = idempotency_service.compute_payload_hash(payload_dict)

        # 1. Check Idempotency in DB
        existing_tx = await self.tx_repo.get_by_idempotency_key(idempotency_key)
        if existing_tx:
            if existing_tx.idempotency_payload_hash != payload_hash:
                raise HTTPException(
                    status_code=status.HTTP_409_CONFLICT,
                    detail="Idempotency key reused with conflicting payload parameters."
                )
            return existing_tx

        # 2. Prevent self-transfer
        if source_account_id == destination_account_id:
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail="Source and destination accounts must be distinct."
            )

        # 3. Fraud check
        approved, fraud_reason, _ = await fraud_service.evaluate_transaction(source_account_id, amount)
        if not approved:
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN,
                detail=f"Transaction blocked by fraud engine: {fraud_reason}"
            )

        # 4. OCC Balance Debit with Exponential Backoff Retries (Max 3 retries)
        max_retries = 3
        debit_success = False
        source_acc = None
        dest_acc = None

        for attempt in range(max_retries):
            # Fetch accounts with consistent lexicographical ID lock ordering to prevent deadlock
            source_acc = await self.account_repo.get_by_id(source_account_id)
            if not source_acc or source_acc.status != "ACTIVE":
                raise HTTPException(
                    status_code=status.HTTP_404_NOT_FOUND,
                    detail=f"Source account {source_account_id} not found or inactive."
                )

            if source_acc.available_balance < amount:
                raise HTTPException(
                    status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                    detail="Insufficient available funds for transaction."
                )

            dest_acc = await self.account_repo.get_by_id(destination_account_id)
            if not dest_acc or dest_acc.status != "ACTIVE":
                raise HTTPException(
                    status_code=status.HTTP_404_NOT_FOUND,
                    detail=f"Destination account {destination_account_id} not found or inactive."
                )

            # Attempt atomic OCC debit
            debit_success = await self.account_repo.atomic_occ_debit(
                account_id=source_account_id,
                amount=amount,
                expected_version=source_acc.version
            )
            if debit_success:
                break
            # Backoff with jitter before retry
            await asyncio.sleep(0.01 * (2 ** attempt))

        if not debit_success:
            raise HTTPException(
                status_code=status.HTTP_409_CONFLICT,
                detail="Transaction aborted due to concurrent balance modification conflict. Please retry."
            )

        # 5. Atomic Credit to destination account
        await self.account_repo.atomic_credit(
            account_id=destination_account_id,
            amount=amount
        )

        # Refresh accounts for updated balances
        await self.session.refresh(source_acc)
        await self.session.refresh(dest_acc)

        # 6. Record Transaction
        tx = Transaction(
            transaction_id=str(uuid.uuid4()),
            idempotency_key=idempotency_key,
            idempotency_payload_hash=payload_hash,
            transaction_type="P2P_TRANSFER",
            source_account_id=source_account_id,
            destination_account_id=destination_account_id,
            amount=amount,
            currency=currency,
            status="COMPLETED",
            correlation_id=correlation_id,
            completed_at=datetime.datetime.utcnow()
        )
        await self.tx_repo.create(tx)

        # 7. Record Balanced Double-Entry Ledger Entries
        # Debit Entry
        debit_entry = LedgerEntry(
            entry_id=str(uuid.uuid4()),
            transaction_id=tx.transaction_id,
            account_id=source_account_id,
            entry_type="DEBIT",
            amount=amount,
            currency=currency,
            balance_after=source_acc.available_balance
        )
        await self.ledger_repo.create_entry(debit_entry)

        # Credit Entry
        credit_entry = LedgerEntry(
            entry_id=str(uuid.uuid4()),
            transaction_id=tx.transaction_id,
            account_id=destination_account_id,
            entry_type="CREDIT",
            amount=amount,
            currency=currency,
            balance_after=dest_acc.available_balance
        )
        await self.ledger_repo.create_entry(credit_entry)

        # 8. Record Outbox Event for Asynchronous Processing
        outbox_event = OutboxEvent(
            event_id=str(uuid.uuid4()),
            aggregate_type="TRANSACTION",
            aggregate_id=tx.transaction_id,
            event_type="PaymentCompleted",
            payload={
                "transaction_id": tx.transaction_id,
                "source_account_id": source_account_id,
                "destination_account_id": destination_account_id,
                "amount": str(amount),
                "currency": currency,
                "status": "COMPLETED",
                "correlation_id": correlation_id
            },
            status="PENDING"
        )
        self.session.add(outbox_event)

        # Commit everything in single atomic ACID transaction
        await self.session.commit()
        await self.session.refresh(tx)

        return tx

    async def execute_reversal(
        self,
        transaction_id: str,
        idempotency_key: str,
        reason: str,
        correlation_id: str = None
    ) -> Transaction:
        correlation_id = correlation_id or str(uuid.uuid4())
        
        orig_tx = await self.tx_repo.get_by_id(transaction_id)
        if not orig_tx:
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND,
                detail=f"Original transaction {transaction_id} not found."
            )

        if orig_tx.status != "COMPLETED":
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail=f"Transaction {transaction_id} cannot be reversed (current status: {orig_tx.status})."
            )

        # Reversal requires debiting destination and crediting source
        source_id = orig_tx.source_account_id
        dest_id = orig_tx.destination_account_id
        amount = orig_tx.amount

        # Check destination has sufficient funds to revert
        dest_acc = await self.account_repo.get_by_id(dest_id)
        if not dest_acc or dest_acc.available_balance < amount:
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                detail="Destination account has insufficient funds to process reversal."
            )

        # Debit destination
        await self.account_repo.atomic_occ_debit(dest_id, amount, dest_acc.version)
        # Credit source
        await self.account_repo.atomic_credit(source_id, amount)

        # Update original status
        orig_tx.status = "REVERSED"
        
        # Create reversal transaction record
        rev_tx = Transaction(
            transaction_id=str(uuid.uuid4()),
            idempotency_key=idempotency_key,
            transaction_type="REVERSAL",
            source_account_id=dest_id,
            destination_account_id=source_id,
            amount=amount,
            currency=orig_tx.currency,
            status="COMPLETED",
            failure_reason=reason,
            correlation_id=correlation_id,
            completed_at=datetime.datetime.utcnow()
        )
        await self.tx_repo.create(rev_tx)

        # Balanced double-entry for reversal
        rev_debit = LedgerEntry(
            entry_id=str(uuid.uuid4()),
            transaction_id=rev_tx.transaction_id,
            account_id=dest_id,
            entry_type="DEBIT",
            amount=amount,
            currency=orig_tx.currency,
            balance_after=dest_acc.available_balance - amount
        )
        await self.ledger_repo.create_entry(rev_debit)

        rev_credit = LedgerEntry(
            entry_id=str(uuid.uuid4()),
            transaction_id=rev_tx.transaction_id,
            account_id=source_id,
            entry_type="CREDIT",
            amount=amount,
            currency=orig_tx.currency,
            balance_after=dest_acc.available_balance
        )
        await self.ledger_repo.create_entry(rev_credit)

        await self.session.commit()
        await self.session.refresh(rev_tx)
        return rev_tx
