#!/usr/bin/env python3
"""
PayScale HTTPE - High-Throughput Circuit Breaker Implementation
Sliding-window failure tracking, state transitions (CLOSED, OPEN, HALF-OPEN),
canary probe trials, and fallback invocation.
Fulfills Day 9 Deliverable.
"""

import time
import threading
import logging
from typing import Callable, Any, List

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")

class CircuitBreakerState:
    CLOSED = "CLOSED"          # Normal operation; calls pass through
    OPEN = "OPEN"              # Dependency failing; fast-fail to fallback
    HALF_OPEN = "HALF_OPEN"    # Testing dependency with limited trial calls

class CircuitBreakerOpenException(Exception):
    pass

class CircuitBreaker:
    def __init__(
        self,
        name: str,
        failure_threshold: int = 3,
        window_seconds: float = 10.0,
        reset_timeout_seconds: float = 15.0,
        half_open_probe_count: int = 5
    ):
        self.name = name
        self.failure_threshold = failure_threshold
        self.window_seconds = window_seconds
        self.reset_timeout_seconds = reset_timeout_seconds
        self.half_open_probe_count = half_open_probe_count

        self.state = CircuitBreakerState.CLOSED
        self.failure_timestamps: List[float] = []
        self.last_state_change = time.time()
        self.half_open_success_count = 0
        self.lock = threading.Lock()

    def _prune_expired_failures(self, current_time: float):
        cutoff = current_time - self.window_seconds
        self.failure_timestamps = [t for t in self.failure_timestamps if t > cutoff]

    def execute(self, func: Callable[[], Any], fallback: Callable[[Exception], Any]) -> Any:
        current_time = time.time()

        with self.lock:
            # Check if OPEN sleep window has expired -> transition to HALF_OPEN
            if self.state == CircuitBreakerState.OPEN:
                if current_time - self.last_state_change >= self.reset_timeout_seconds:
                    logging.info(f"[{self.name}] Reset timeout expired ({self.reset_timeout_seconds}s). Transitioning to HALF_OPEN probe mode.")
                    self.state = CircuitBreakerState.HALF_OPEN
                    self.last_state_change = current_time
                    self.half_open_success_count = 0
                else:
                    # Still OPEN -> fast-fail immediately
                    logging.debug(f"[{self.name}] Breaker is OPEN. Executing fallback.")
                    return fallback(CircuitBreakerOpenException(f"Circuit breaker {self.name} is OPEN"))

        # In HALF_OPEN or CLOSED: attempt actual execution
        try:
            result = func()
            self._on_success()
            return result
        except Exception as e:
            self._on_failure(e)
            return fallback(e)

    def _on_success(self):
        with self.lock:
            current_time = time.time()
            if self.state == CircuitBreakerState.HALF_OPEN:
                self.half_open_success_count += 1
                logging.info(f"[{self.name}] HALF_OPEN probe success ({self.half_open_success_count}/{self.half_open_probe_count})")
                if self.half_open_success_count >= self.half_open_probe_count:
                    logging.info(f"[{self.name}] All {self.half_open_probe_count} probes succeeded. Transitioning to CLOSED.")
                    self.state = CircuitBreakerState.CLOSED
                    self.last_state_change = current_time
                    self.failure_timestamps.clear()
            elif self.state == CircuitBreakerState.CLOSED:
                self._prune_expired_failures(current_time)

    def _on_failure(self, error: Exception):
        with self.lock:
            current_time = time.time()
            logging.warning(f"[{self.name}] Call failed with error: {error}")
            
            if self.state == CircuitBreakerState.HALF_OPEN:
                logging.error(f"[{self.name}] Probe failed in HALF_OPEN. Re-tripping to OPEN.")
                self.state = CircuitBreakerState.OPEN
                self.last_state_change = current_time
            elif self.state == CircuitBreakerState.CLOSED:
                self.failure_timestamps.append(current_time)
                self._prune_expired_failures(current_time)
                if len(self.failure_timestamps) >= self.failure_threshold:
                    logging.error(f"[{self.name}] Threshold breached ({len(self.failure_timestamps)} failures in {self.window_seconds}s). Tripping to OPEN!")
                    self.state = CircuitBreakerState.OPEN
                    self.last_state_change = current_time


if __name__ == "__main__":
    # Demonstration using CB-FRAUD parameters (3 failures / 10s, 2s reset for test)
    cb_fraud = CircuitBreaker("CB-FRAUD", failure_threshold=3, window_seconds=10.0, reset_timeout_seconds=2.0, half_open_probe_count=2)

    def failing_fraud_service():
        raise TimeoutError("Downstream Fraud Service timed out after 15ms")

    def fallback_fraud_review(exc):
        return {"action": "ALLOW_FLAGGED", "reason": "CB_FRAUD_OPEN_FALLBACK", "risk_score": 50}

    print("--- 1. Simulating 3 consecutive failures to trip the breaker ---")
    for i in range(3):
        res = cb_fraud.execute(failing_fraud_service, fallback_fraud_review)
        print(f"Call {i+1} Result: {res}")

    print(f"Breaker state now: {cb_fraud.state}")

    print("--- 2. Call while OPEN (fast-fails immediately without hitting service) ---")
    res = cb_fraud.execute(failing_fraud_service, fallback_fraud_review)
    print("Fast-fail Result:", res)

    print("--- 3. Waiting 2.1s for reset timeout... ---")
    time.sleep(2.1)

    def recovered_fraud_service():
        return {"action": "ALLOW", "risk_score": 12}

    print("--- 4. Probing in HALF_OPEN mode ---")
    p1 = cb_fraud.execute(recovered_fraud_service, fallback_fraud_review)
    print("Probe 1:", p1, "| State:", cb_fraud.state)
    p2 = cb_fraud.execute(recovered_fraud_service, fallback_fraud_review)
    print("Probe 2:", p2, "| State:", cb_fraud.state)
