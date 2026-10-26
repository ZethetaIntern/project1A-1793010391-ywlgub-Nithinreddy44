import os
from pydantic_settings import BaseSettings
from typing import Optional

class Settings(BaseSettings):
    APP_NAME: str = "PayScale High-Throughput Transaction Processing Engine"
    ENVIRONMENT: str = "development"
    DEBUG: bool = True
    API_V1_STR: str = "/api/v1"
    
    # Database
    DATABASE_URL: str = os.getenv("DATABASE_URL", "sqlite+aiosqlite:///./payscale_dev.db")
    
    # Redis
    REDIS_URL: str = os.getenv("REDIS_URL", "redis://localhost:6379/0")
    
    # Kafka
    KAFKA_BOOTSTRAP_SERVERS: str = os.getenv("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092")
    
    # Security
    JWT_SECRET_KEY: str = "payscale-super-secret-key-dev-only-2026"
    JWT_ALGORITHM: str = "HS256"
    WEBHOOK_SECRET: str = "payscale-webhook-secret-key"
    
    # Financial Invariants
    IDEMPOTENCY_TTL_SECONDS: int = 86400  # 24 hours per spec
    FRAUD_SLA_MS: int = 15                # 15 ms target
    TARGET_SUSTAINED_TPS: int = 12000
    BURST_TPS: int = 18000
    
    class Config:
        env_file = ".env"
        extra = "ignore"

settings = Settings()
