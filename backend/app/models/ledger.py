import uuid
import datetime
from decimal import Decimal
from sqlalchemy.orm import Mapped, mapped_column
from sqlalchemy import String, Numeric, DateTime, Index, CheckConstraint
from app.db.base import Base

class LedgerEntry(Base):
    __tablename__ = "ledger_entries"

    entry_id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid.uuid4()))
    transaction_id: Mapped[str] = mapped_column(String(36), nullable=False, index=True)
    account_id: Mapped[str] = mapped_column(String(36), nullable=False, index=True)
    
    entry_type: Mapped[str] = mapped_column(String(10), nullable=False)  # DEBIT, CREDIT
    amount: Mapped[Decimal] = mapped_column(Numeric(18, 4), nullable=False)
    currency: Mapped[str] = mapped_column(String(3), nullable=False, default="INR")
    
    balance_after: Mapped[Decimal] = mapped_column(Numeric(18, 4), nullable=False)
    created_at: Mapped[datetime.datetime] = mapped_column(DateTime(timezone=True), default=datetime.datetime.utcnow, nullable=False)

    __table_args__ = (
        CheckConstraint("amount > 0.0000", name="chk_ledger_amount_positive"),
        CheckConstraint("entry_type IN ('DEBIT', 'CREDIT')", name="chk_ledger_entry_type_valid"),
        Index("idx_ledger_account_created", "account_id", "created_at"),
    )
