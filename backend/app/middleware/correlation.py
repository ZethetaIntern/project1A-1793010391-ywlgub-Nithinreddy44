import time
import uuid
import logging
from typing import Callable
from fastapi import Request, Response
from starlette.middleware.base import BaseHTTPMiddleware
from starlette.responses import JSONResponse

logger = logging.getLogger("httpe.access")

class CorrelationAndLoggingMiddleware(BaseHTTPMiddleware):
    async def dispatch(self, request: Request, call_next: Callable) -> Response:
        correlation_id = request.headers.get("X-Correlation-ID") or str(uuid.uuid4())
        request.state.correlation_id = correlation_id
        
        start_time = time.perf_counter()
        try:
            response = await call_next(request)
            process_time_ms = (time.perf_counter() - start_time) * 1000.0
            response.headers["X-Correlation-ID"] = correlation_id
            response.headers["X-Response-Time-Ms"] = f"{process_time_ms:.2f}"
            
            logger.info(
                f"method={request.method} path={request.url.path} "
                f"status={response.status_code} latency_ms={process_time_ms:.2f} "
                f"correlation_id={correlation_id}"
            )
            return response
        except Exception as exc:
            process_time_ms = (time.perf_counter() - start_time) * 1000.0
            logger.error(
                f"method={request.method} path={request.url.path} "
                f"error={str(exc)} latency_ms={process_time_ms:.2f} "
                f"correlation_id={correlation_id}"
            )
            return JSONResponse(
                status_code=500,
                content={
                    "success": False,
                    "correlation_id": correlation_id,
                    "error_code": "INTERNAL_SERVER_ERROR",
                    "message": "An unexpected error occurred. Please contact support with the correlation ID."
                },
                headers={"X-Correlation-ID": correlation_id}
            )
