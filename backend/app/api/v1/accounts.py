import uuid
from typing import List, Optional
from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy.ext.asyncio import AsyncSession
from app.db.session import get_db_session
from app.models.account import Account
from app.repositories.account_repo import AccountRepository
from app.schemas.payment import AccountCreate, AccountResponse

router = APIRouter(prefix="/accounts", tags=["Accounts"])

@router.post("", response_model=AccountResponse, status_code=status.HTTP_201_CREATED)
async def create_account(payload: AccountCreate, session: AsyncSession = Depends(get_db_session)):
    repo = AccountRepository(session)
    existing = await repo.get_by_account_number(payload.account_number)
    if existing:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail=f"Account with number {payload.account_number} already exists."
        )

    account = Account(
        account_id=str(uuid.uuid4()),
        user_id=payload.user_id,
        account_number=payload.account_number,
        account_type=payload.account_type,
        currency=payload.currency,
        available_balance=payload.initial_balance,
        ledger_balance=payload.initial_balance,
        version=1,
        status="ACTIVE",
        tier=payload.tier
    )
    created = await repo.create(account)
    await session.commit()
    await session.refresh(created)
    return created

@router.get("/{account_id}", response_model=AccountResponse)
async def get_account(account_id: str, session: AsyncSession = Depends(get_db_session)):
    repo = AccountRepository(session)
    account = await repo.get_by_id(account_id)
    if not account:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"Account {account_id} not found."
        )
    return account
