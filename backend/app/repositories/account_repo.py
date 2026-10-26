from typing import Optional, List
from decimal import Decimal
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy import select, update, and_
from app.models.account import Account

class AccountRepository:
    def __init__(self, session: AsyncSession):
        self.session = session

    async def get_by_id(self, account_id: str) -> Optional[Account]:
        result = await self.session.execute(select(Account).where(Account.account_id == account_id))
        return result.scalar_one_or_none()

    async def get_by_account_number(self, account_number: str) -> Optional[Account]:
        result = await self.session.execute(select(Account).where(Account.account_number == account_number))
        return result.scalar_one_or_none()

    async def create(self, account: Account) -> Account:
        self.session.add(account)
        await self.session.flush()
        return account

    async def atomic_occ_debit(self, account_id: str, amount: Decimal, expected_version: int) -> bool:
        """
        Executes atomic debit with Optimistic Concurrency Control (OCC).
        Updates available_balance and ledger_balance and increments version
        ONLY IF current version == expected_version AND available_balance >= amount.
        Returns True if 1 row was updated, False if conflict or insufficient funds.
        """
        stmt = (
            update(Account)
            .where(
                and_(
                    Account.account_id == account_id,
                    Account.version == expected_version,
                    Account.available_balance >= amount,
                    Account.status == "ACTIVE"
                )
            )
            .values(
                available_balance=Account.available_balance - amount,
                ledger_balance=Account.ledger_balance - amount,
                version=Account.version + 1
            )
        )
        result = await self.session.execute(stmt)
        return result.rowcount == 1

    async def atomic_credit(self, account_id: str, amount: Decimal) -> bool:
        """
        Executes atomic credit to destination account, incrementing version and balances.
        """
        stmt = (
            update(Account)
            .where(
                and_(
                    Account.account_id == account_id,
                    Account.status == "ACTIVE"
                )
            )
            .values(
                available_balance=Account.available_balance + amount,
                ledger_balance=Account.ledger_balance + amount,
                version=Account.version + 1
            )
        )
        result = await self.session.execute(stmt)
        return result.rowcount == 1
