import uuid
import datetime
from decimal import Decimal
from typing import Optional
from sqlalchemy.orm import Mapped, mapped_column
from sqlalchemy import String, Numeric, Integer, DateTime, Index, CheckConstraint
from app.db.base import Base

class MerchantSettlement(Base):
    __tablename__ = "merchant_settlements"

    settlement_id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid.uuid4()))
    batch_id: Mapped[str] = mapped_column(String(64), nullable=False, index=True)
    merchant_id: Mapped[str] = mapped_column(String(36), nullable=False, index=True)
    
    total_transactions: Mapped[int] = mapped_column(Integer, nullable=False)
    gross_amount: Mapped[Decimal] = mapped_column(Numeric(18, 4), nullable=False)
    fee_amount: Mapped[Decimal] = mapped_column(Numeric(18, 4), nullable=False, default=Decimal("0.0000"))
    net_amount: Mapped[Decimal] = mapped_column(Numeric(18, 4), nullable=False)
    currency: Mapped[str] = mapped_column(String(3), nullable=False, default="INR")
    
    status: Mapped[str] = mapped_column(String(20), nullable=False, default="PROCESSING")  # PROCESSING, SETTLED, FAILED
    created_at: Mapped[datetime.datetime] = mapped_column(DateTime(timezone=True), default=datetime.datetime.utcnow, nullable=False)
    settled_at: Mapped[Optional[datetime.datetime]] = mapped_column(DateTime(timezone=True), nullable=True)

    __table_args__ = (
        CheckConstraint("status IN ('PROCESSING', 'SETTLED', 'FAILED')", name="chk_settlement_status"),
        Index("idx_settlement_batch_status", "batch_id", "status"),
    )

class FraudRule(Base):
    __tablename__ = "fraud_rules"

    rule_id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid.uuid4()))
    rule_name: Mapped[str] = mapped_column(String(100), unique=True, nullable=False)
    rule_type: Mapped[str] = mapped_column(String(50), nullable=False)  # VELOCITY, AMOUNT_THRESHOLD, GEO_IP, BLACKLIST
    threshold_value: Mapped[Decimal] = mapped_column(Numeric(18, 4), nullable=False)
    action: Mapped[str] = mapped_column(String(20), nullable=False, default="REJECT")  # ALLOW, REVIEW, REJECT
    is_active: Mapped[bool] = mapped_column(default=True, nullable=False)

class NotificationLog(Base):
    __tablename__ = "notification_log"

    notification_id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid.uuid4()))
    channel: Mapped[str] = mapped_column(String(20), nullable=False)  # WEBSOCKET, WEBHOOK, SMS, EMAIL
    recipient: Mapped[str] = mapped_column(String(255), nullable=False)
    payload_summary: Mapped[str] = mapped_column(String(500), nullable=False)
    delivery_status: Mapped[str] = mapped_column(String(20), nullable=False, default="SENT")  # SENT, FAILED, DELIVERED
    created_at: Mapped[datetime.datetime] = mapped_column(DateTime(timezone=True), default=datetime.datetime.utcnow, nullable=False)
