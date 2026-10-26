from contextlib import asynccontextmanager
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app.core.config import settings
from app.db.base import Base
from app.db.session import engine
from app.middleware.correlation import CorrelationAndLoggingMiddleware
from app.api.v1.accounts import router as accounts_router
from app.api.v1.payments import router as payments_router
from app.api.v1.transactions import router as transactions_router
from app.api.v1.health import router as health_router

@asynccontextmanager
async def lifespan(app: FastAPI):
    # Initialize database tables on startup if in development/test
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)
    yield
    # Graceful shutdown: close connection pools
    await engine.dispose()

def create_application() -> FastAPI:
    app = FastAPI(
        title="PayScale High-Throughput Transaction Processing Engine (HTTPE)",
        description="High-Throughput, Double-Entry, OCC-Controlled Core Financial Engine.",
        version="1.0.0",
        docs_url="/docs",
        redoc_url="/redoc",
        openapi_url="/openapi.json",
        lifespan=lifespan
    )

    # CORS configuration
    app.add_middleware(
        CORSMiddleware,
        allow_origins=["*"],
        allow_credentials=True,
        allow_methods=["*"],
        allow_headers=["*"],
    )

    # Correlation ID & structured error middleware
    app.add_middleware(CorrelationAndLoggingMiddleware)

    # Register Routers
    app.include_router(health_router)
    app.include_router(accounts_router, prefix=settings.API_V1_STR)
    app.include_router(payments_router, prefix=settings.API_V1_STR)
    app.include_router(transactions_router, prefix=settings.API_V1_STR)

    return app

app = create_application()
