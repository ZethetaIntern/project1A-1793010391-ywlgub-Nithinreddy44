import uuid
import datetime
from decimal import Decimal
from typing import Optional
from sqlalchemy.orm import Mapped, mapped_column
from sqlalchemy import String, Numeric, DateTime, JSON, Index, CheckConstraint
from app.db.base import Base

class Transaction(Base):
    __tablename__ = "transactions"

    transaction_id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid.uuid4()))
    idempotency_key: Mapped[str] = mapped_column(String(128), unique=True, nullable=False)
    idempotency_payload_hash: Mapped[Optional[str]] = mapped_column(String(64), nullable=True)
    
    transaction_type: Mapped[str] = mapped_column(String(30), nullable=False)  # P2P_TRANSFER, MERCHANT_PAYMENT, REFUND, REVERSAL
    source_account_id: Mapped[Optional[str]] = mapped_column(String(36), nullable=True, index=True)
    destination_account_id: Mapped[Optional[str]] = mapped_column(String(36), nullable=True, index=True)
    
    amount: Mapped[Decimal] = mapped_column(Numeric(18, 4), nullable=False)
    currency: Mapped[str] = mapped_column(String(3), nullable=False, default="INR")
    
    status: Mapped[str] = mapped_column(String(30), nullable=False, default="PENDING")  # PENDING, PROCESSING, COMPLETED, FAILED, REVERSED
    failure_reason: Mapped[Optional[str]] = mapped_column(String(255), nullable=True)
    
    correlation_id: Mapped[str] = mapped_column(String(64), nullable=False, default=lambda: str(uuid.uuid4()), index=True)
    saga_id: Mapped[Optional[str]] = mapped_column(String(36), nullable=True, index=True)
    
    created_at: Mapped[datetime.datetime] = mapped_column(DateTime(timezone=True), default=datetime.datetime.utcnow, nullable=False)
    completed_at: Mapped[Optional[datetime.datetime]] = mapped_column(DateTime(timezone=True), nullable=True)
    meta_info: Mapped[Optional[dict]] = mapped_column(JSON, nullable=True)

    __table_args__ = (
        CheckConstraint("amount > 0.0000", name="chk_transaction_amount_positive"),
        CheckConstraint(
            "status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED', 'REVERSED')",
            name="chk_transaction_status_valid"
        ),
        Index("idx_transactions_source_created", "source_account_id", "created_at"),
        Index("idx_transactions_dest_created", "destination_account_id", "created_at"),
    )
