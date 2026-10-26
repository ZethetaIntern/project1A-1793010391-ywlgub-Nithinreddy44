import uuid
from typing import List, Optional
from fastapi import APIRouter, Depends, HTTPException, Request, status
from sqlalchemy.ext.asyncio import AsyncSession
from app.db.session import get_db_session
from app.repositories.transaction_repo import TransactionRepository
from app.services.payment_service import PaymentService
from app.schemas.payment import TransactionResponse, ReversalRequest

router = APIRouter(prefix="/transactions", tags=["Transactions"])

@router.get("/{transaction_id}", response_model=TransactionResponse)
async def get_transaction(transaction_id: str, session: AsyncSession = Depends(get_db_session)):
    repo = TransactionRepository(session)
    tx = await repo.get_by_id(transaction_id)
    if not tx:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"Transaction {transaction_id} not found."
        )
    return tx

@router.get("/accounts/{account_id}/history", response_model=List[TransactionResponse])
async def get_account_transactions(
    account_id: str,
    limit: int = 50,
    cursor: Optional[str] = None,
    session: AsyncSession = Depends(get_db_session)
):
    repo = TransactionRepository(session)
    return await repo.get_account_history(account_id, limit, cursor)

@router.post("/{transaction_id}/reversals", response_model=TransactionResponse)
async def reverse_transaction(
    transaction_id: str,
    payload: ReversalRequest,
    request: Request,
    session: AsyncSession = Depends(get_db_session)
):
    correlation_id = getattr(request.state, "correlation_id", str(uuid.uuid4()))
    service = PaymentService(session)
    rev_tx = await service.execute_reversal(
        transaction_id=transaction_id,
        idempotency_key=payload.idempotency_key,
        reason=payload.reason,
        correlation_id=correlation_id
    )
    return rev_tx
