from typing import Optional, List, Tuple
from decimal import Decimal
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy import select, func, or_
from app.models.transaction import Transaction
from app.models.ledger import LedgerEntry

class TransactionRepository:
    def __init__(self, session: AsyncSession):
        self.session = session

    async def get_by_id(self, transaction_id: str) -> Optional[Transaction]:
        result = await self.session.execute(
            select(Transaction).where(Transaction.transaction_id == transaction_id)
        )
        return result.scalar_one_or_none()

    async def get_by_idempotency_key(self, idempotency_key: str) -> Optional[Transaction]:
        result = await self.session.execute(
            select(Transaction).where(Transaction.idempotency_key == idempotency_key)
        )
        return result.scalar_one_or_none()

    async def create(self, transaction: Transaction) -> Transaction:
        self.session.add(transaction)
        await self.session.flush()
        return transaction

    async def get_account_history(
        self, account_id: str, limit: int = 50, cursor: Optional[str] = None
    ) -> List[Transaction]:
        query = select(Transaction).where(
            or_(
                Transaction.source_account_id == account_id,
                Transaction.destination_account_id == account_id
            )
        ).order_by(Transaction.created_at.desc()).limit(limit)
        
        result = await self.session.execute(query)
        return list(result.scalars().all())


class LedgerRepository:
    def __init__(self, session: AsyncSession):
        self.session = session

    async def create_entry(self, entry: LedgerEntry) -> LedgerEntry:
        self.session.add(entry)
        await self.session.flush()
        return entry

    async def get_entries_for_transaction(self, transaction_id: str) -> List[LedgerEntry]:
        result = await self.session.execute(
            select(LedgerEntry).where(LedgerEntry.transaction_id == transaction_id)
        )
        return list(result.scalars().all())

    async def calculate_reconciliation_totals(self) -> Tuple[Decimal, Decimal, int]:
        """
        Calculates sum of all DEBIT entries and sum of all CREDIT entries, plus total count.
        Double-entry financial invariant requires: sum(DEBIT) == sum(CREDIT).
        """
        debit_result = await self.session.execute(
            select(func.coalesce(func.sum(LedgerEntry.amount), Decimal("0.0000")))
            .where(LedgerEntry.entry_type == "DEBIT")
        )
        total_debits = debit_result.scalar_one()

        credit_result = await self.session.execute(
            select(func.coalesce(func.sum(LedgerEntry.amount), Decimal("0.0000")))
            .where(LedgerEntry.entry_type == "CREDIT")
        )
        total_credits = credit_result.scalar_one()

        count_result = await self.session.execute(select(func.count(LedgerEntry.entry_id)))
        total_count = count_result.scalar_one()

        return total_debits, total_credits, total_count
