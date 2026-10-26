import uuid
from typing import List, Optional
from fastapi import APIRouter, Depends, Request, status
from sqlalchemy.ext.asyncio import AsyncSession
from app.db.session import get_db_session
from app.services.payment_service import PaymentService
from app.schemas.payment import (
    P2PPaymentRequest,
    MerchantPaymentRequest,
    ReversalRequest,
    TransactionResponse
)

router = APIRouter(prefix="/payments", tags=["Payments"])

@router.post("/p2p", response_model=TransactionResponse, status_code=status.HTTP_201_CREATED)
async def submit_p2p_payment(
    payload: P2PPaymentRequest,
    request: Request,
    session: AsyncSession = Depends(get_db_session)
):
    correlation_id = getattr(request.state, "correlation_id", str(uuid.uuid4()))
    service = PaymentService(session)
    tx = await service.execute_p2p_payment(
        idempotency_key=payload.idempotency_key,
        source_account_id=payload.source_account_id,
        destination_account_id=payload.destination_account_id,
        amount=payload.amount,
        currency=payload.currency,
        correlation_id=correlation_id
    )
    return tx

@router.post("/merchant", response_model=TransactionResponse, status_code=status.HTTP_201_CREATED)
async def submit_merchant_payment(
    payload: MerchantPaymentRequest,
    request: Request,
    session: AsyncSession = Depends(get_db_session)
):
    correlation_id = getattr(request.state, "correlation_id", str(uuid.uuid4()))
    service = PaymentService(session)
    tx = await service.execute_p2p_payment(
        idempotency_key=payload.idempotency_key,
        source_account_id=payload.source_account_id,
        destination_account_id=payload.destination_account_id,
        amount=payload.amount,
        currency=payload.currency,
        correlation_id=correlation_id
    )
    return tx
