import uuid
from decimal import Decimal
from typing import Optional
from sqlalchemy.orm import Mapped, mapped_column
from sqlalchemy import String, Numeric, BigInteger, CheckConstraint, Index
from app.db.base import Base, TimestampMixin

class Account(Base, TimestampMixin):
    __tablename__ = "accounts"

    account_id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid.uuid4()))
    user_id: Mapped[str] = mapped_column(String(36), nullable=False, index=True)
    account_number: Mapped[str] = mapped_column(String(20), unique=True, nullable=False)
    account_type: Mapped[str] = mapped_column(String(20), nullable=False, default="USER")  # USER, MERCHANT, SYSTEM
    currency: Mapped[str] = mapped_column(String(3), nullable=False, default="INR")
    
    # Financial Balances: 18 digits total, 4 decimal places
    available_balance: Mapped[Decimal] = mapped_column(Numeric(18, 4), nullable=False, default=Decimal("0.0000"))
    ledger_balance: Mapped[Decimal] = mapped_column(Numeric(18, 4), nullable=False, default=Decimal("0.0000"))
    
    # Optimistic Concurrency Control (OCC) version
    version: Mapped[int] = mapped_column(BigInteger, nullable=False, default=1)
    
    status: Mapped[str] = mapped_column(String(20), nullable=False, default="ACTIVE")  # ACTIVE, FROZEN, CLOSED
    tier: Mapped[str] = mapped_column(String(20), nullable=False, default="STANDARD")  # STANDARD, PREMIUM, ENTERPRISE
    shard_key: Mapped[int] = mapped_column(BigInteger, nullable=False, default=0)

    __table_args__ = (
        CheckConstraint("available_balance >= 0.0000", name="chk_account_available_balance_non_negative"),
        CheckConstraint("ledger_balance >= 0.0000", name="chk_account_ledger_balance_non_negative"),
        CheckConstraint("account_type IN ('USER', 'MERCHANT', 'SYSTEM')", name="chk_account_type_valid"),
        CheckConstraint("status IN ('ACTIVE', 'FROZEN', 'CLOSED')", name="chk_account_status_valid"),
        Index("idx_accounts_user_status", "user_id", "status"),
        Index("idx_accounts_shard_key", "shard_key"),
    )
