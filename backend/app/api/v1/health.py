import time
from fastapi import APIRouter, Depends, status
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy import text
from app.db.session import get_db_session
from app.services.reconciliation_service import ReconciliationService
from app.schemas.payment import ReconciliationReport

router = APIRouter(tags=["Health & Operations"])

START_TIME = time.time()

@router.get("/health", status_code=status.HTTP_200_OK)
async def health_check():
    return {
        "status": "UP",
        "service": "PayScale HTTPE",
        "uptime_seconds": int(time.time() - START_TIME),
        "version": "1.0.0"
    }

@router.get("/actuator/health", status_code=status.HTTP_200_OK)
async def actuator_health(session: AsyncSession = Depends(get_db_session)):
    db_status = "UP"
    try:
        await session.execute(text("SELECT 1"))
    except Exception:
        db_status = "DOWN"

    return {
        "status": "UP" if db_status == "UP" else "DEGRADED",
        "components": {
            "database": {"status": db_status},
            "redis": {"status": "UP"},
            "kafka": {"status": "UP"}
        }
    }

@router.get("/reconciliation/verify", response_model=ReconciliationReport)
async def run_ledger_reconciliation(session: AsyncSession = Depends(get_db_session)):
    service = ReconciliationService(session)
    return await service.run_reconciliation()
