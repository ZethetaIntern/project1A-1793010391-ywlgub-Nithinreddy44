import uuid
from decimal import Decimal
from typing import Optional
from sqlalchemy.orm import Mapped, mapped_column
from sqlalchemy import String, Numeric, Index, CheckConstraint
from app.db.base import Base, TimestampMixin

class User(Base, TimestampMixin):
    __tablename__ = "users"

    user_id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid.uuid4()))
    phone: Mapped[str] = mapped_column(String(15), unique=True, nullable=False, index=True)
    email: Mapped[str] = mapped_column(String(255), unique=True, nullable=False, index=True)
    full_name: Mapped[str] = mapped_column(String(100), nullable=False)
    kyc_status: Mapped[str] = mapped_column(String(20), nullable=False, default="VERIFIED")  # PENDING, VERIFIED, REJECTED
    risk_score: Mapped[Decimal] = mapped_column(Numeric(3, 2), nullable=False, default=Decimal("0.10"))
    status: Mapped[str] = mapped_column(String(20), nullable=False, default="ACTIVE")

    __table_args__ = (
        CheckConstraint("kyc_status IN ('PENDING', 'VERIFIED', 'REJECTED')", name="chk_user_kyc_status"),
        CheckConstraint("status IN ('ACTIVE', 'SUSPENDED', 'BLOCKED')", name="chk_user_status"),
        CheckConstraint("risk_score >= 0.00 AND risk_score <= 1.00", name="chk_user_risk_score_range"),
    )
