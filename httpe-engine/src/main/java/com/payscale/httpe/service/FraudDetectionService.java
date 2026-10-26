package com.payscale.httpe.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class FraudDetectionService {

    private static final Logger log = LoggerFactory.getLogger(FraudDetectionService.class);
    private static final String CB_FRAUD = "CB-FRAUD";

    private final CircuitBreakerService circuitBreakerService;

    // In-memory velocity tracking: accountId -> timestamp of last transactions
    private final Map<UUID, Long> recentTransactionVelocity = new ConcurrentHashMap<>();

    public FraudDetectionService(CircuitBreakerService circuitBreakerService) {
        this.circuitBreakerService = circuitBreakerService;
    }

    public static class FraudResult {
        private final boolean approved;
        private final String action; // ALLOW, DENY, STEP_UP_OTP, FLAG_MANUAL_REVIEW
        private final String reason;
        private final long evaluationTimeMs;

        public FraudResult(boolean approved, String action, String reason, long evaluationTimeMs) {
            this.approved = approved;
            this.action = action;
            this.reason = reason;
            this.evaluationTimeMs = evaluationTimeMs;
        }

        public boolean isApproved() { return approved; }
        public String getAction() { return action; }
        public String getReason() { return reason; }
        public long getEvaluationTimeMs() { return evaluationTimeMs; }
    }

    /**
     * Real-time pre-authorization evaluation designed for strict 15 ms SLA.
     */
    public FraudResult evaluateTransaction(UUID accountId, BigDecimal amount, String ipAddress, String deviceId) {
        long startTime = System.currentTimeMillis();

        return circuitBreakerService.executeWithCircuitBreaker(
            CB_FRAUD,
            3,  // 3 failures
            15, // 15 seconds reset timeout
            () -> {
                // Rule 1: High Value Check (> 50,000 INR requires OTP)
                if (amount != null && amount.compareTo(new BigDecimal("50000.0000")) > 0) {
                    long duration = System.currentTimeMillis() - startTime;
                    log.info("Fraud Check: High value transfer requires step-up OTP (duration: {}ms)", duration);
                    return new FraudResult(true, "STEP_UP_OTP", "HIGH_VALUE_THRESHOLD", duration);
                }

                // Rule 2: Velocity Check (Limit rapid transactions within 100ms)
                long now = System.currentTimeMillis();
                Long lastTxTime = recentTransactionVelocity.put(accountId, now);
                if (lastTxTime != null && (now - lastTxTime) < 10) {
                    // Under 10ms repeat transaction -> Velocity burst alert
                    long duration = System.currentTimeMillis() - startTime;
                    log.warn("Fraud Alert: Velocity threshold breached for account {} (gap: {}ms)", accountId, now - lastTxTime);
                    return new FraudResult(false, "DENY", "VELOCITY_THRESHOLD_EXCEEDED", duration);
                }

                long duration = System.currentTimeMillis() - startTime;
                log.debug("Fraud Check: APPROVED in {}ms for account {}", duration, accountId);
                return new FraudResult(true, "ALLOW", "APPROVED", duration);
            },
            () -> {
                // Fallback Behavior (CB-FRAUD OPEN): Auto-approve txns < ₹5,000 with MANUAL_REVIEW_FLAGGED
                long duration = System.currentTimeMillis() - startTime;
                if (amount != null && amount.compareTo(new BigDecimal("5000.0000")) <= 0) {
                    log.warn("[CB-FRAUD OPEN] Fallback: Approving low-value txn with MANUAL_REVIEW_FLAGGED");
                    return new FraudResult(true, "FLAG_MANUAL_REVIEW", "CIRCUIT_BREAKER_FALLBACK_ALLOW", duration);
                } else {
                    log.warn("[CB-FRAUD OPEN] Fallback: Rejecting high-value txn during fraud service outage");
                    return new FraudResult(false, "DENY", "CIRCUIT_BREAKER_OPEN_REJECT", duration);
                }
            }
        );
    }
}
