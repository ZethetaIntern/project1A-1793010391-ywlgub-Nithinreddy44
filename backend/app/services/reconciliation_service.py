import datetime
from decimal import Decimal
from sqlalchemy.ext.asyncio import AsyncSession
from app.repositories.transaction_repo import LedgerRepository
from app.schemas.payment import ReconciliationReport

class ReconciliationService:
    def __init__(self, session: AsyncSession):
        self.session = session
        self.ledger_repo = LedgerRepository(session)

    async def run_reconciliation(self) -> ReconciliationReport:
        """
        Validates the fundamental double-entry invariant:
        Total Debits == Total Credits across the entire ledger.
        """
        total_debits, total_credits, count = await self.ledger_repo.calculate_reconciliation_totals()
        discrepancy = abs(total_debits - total_credits)
        is_balanced = (discrepancy == Decimal("0.0000"))

        return ReconciliationReport(
            is_balanced=is_balanced,
            total_debits=total_debits,
            total_credits=total_credits,
            discrepancy=discrepancy,
            checked_entries_count=count,
            checked_at=datetime.datetime.utcnow()
        )
