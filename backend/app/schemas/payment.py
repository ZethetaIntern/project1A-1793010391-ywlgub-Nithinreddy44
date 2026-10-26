from pydantic import BaseModel, Field, ConfigDict
from decimal import Decimal
from typing import Optional, List, Dict, Any
import datetime

# --- Common / Envelope Schemas ---
class ApiResponse(BaseModel):
    success: bool = True
    correlation_id: str
    message: str = "Operation completed successfully"
    data: Optional[Any] = None

# --- Account Schemas ---
class AccountCreate(BaseModel):
    user_id: str
    account_number: str
    account_type: str = Field(default="USER", description="USER, MERCHANT, SYSTEM")
    currency: str = Field(default="INR", max_length=3)
    initial_balance: Decimal = Field(default=Decimal("0.0000"), ge=0)
    tier: str = Field(default="STANDARD")

class AccountResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    account_id: str
    user_id: str
    account_number: str
    account_type: str
    currency: str
    available_balance: Decimal
    ledger_balance: Decimal
    version: int
    status: str
    tier: str
    created_at: datetime.datetime

# --- Payment & Transaction Schemas ---
class P2PPaymentRequest(BaseModel):
    idempotency_key: str = Field(..., max_length=128)
    source_account_id: str
    destination_account_id: str
    amount: Decimal = Field(..., gt=0, decimal_places=4)
    currency: str = Field(default="INR", max_length=3)
    note: Optional[str] = None

class MerchantPaymentRequest(BaseModel):
    idempotency_key: str = Field(..., max_length=128)
    source_account_id: str
    merchant_id: str
    destination_account_id: str
    amount: Decimal = Field(..., gt=0, decimal_places=4)
    currency: str = Field(default="INR", max_length=3)
    merchant_order_id: str
    metadata: Optional[Dict[str, Any]] = None

class ReversalRequest(BaseModel):
    idempotency_key: str = Field(..., max_length=128)
    reason: str = Field(..., max_length=255)

class TransactionResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    transaction_id: str
    idempotency_key: str
    transaction_type: str
    source_account_id: Optional[str]
    destination_account_id: Optional[str]
    amount: Decimal
    currency: str
    status: str
    failure_reason: Optional[str]
    correlation_id: str
    saga_id: Optional[str]
    created_at: datetime.datetime
    completed_at: Optional[datetime.datetime]

class LedgerEntryResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    entry_id: str
    transaction_id: str
    account_id: str
    entry_type: str
    amount: Decimal
    currency: str
    balance_after: Decimal
    created_at: datetime.datetime

# --- Settlement Schemas ---
class BatchSettlementRequest(BaseModel):
    batch_id: str
    merchant_id: str
    merchant_account_id: str
    transaction_ids: List[str]

class BatchSettlementResponse(BaseModel):
    settlement_id: str
    batch_id: str
    merchant_id: str
    total_transactions: int
    gross_amount: Decimal
    fee_amount: Decimal
    net_amount: Decimal
    status: str
    created_at: datetime.datetime

# --- Reconciliation Schemas ---
class ReconciliationReport(BaseModel):
    is_balanced: bool
    total_debits: Decimal
    total_credits: Decimal
    discrepancy: Decimal
    checked_entries_count: int
    checked_at: datetime.datetime
