package com.payscale.httpe.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

@Service
public class CircuitBreakerService {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreakerService.class);

    public enum State { CLOSED, OPEN, HALF_OPEN }

    private static class CircuitState {
        State state = State.CLOSED;
        int failureCount = 0;
        Instant lastFailureTime = Instant.now();
        Instant lastStateChange = Instant.now();
        int halfOpenProbes = 0;
    }

    private final Map<String, CircuitState> circuits = new ConcurrentHashMap<>();

    public <T> T executeWithCircuitBreaker(String name, int failureThreshold, int resetTimeoutSeconds,
                                          Supplier<T> action, Supplier<T> fallback) {
        CircuitState circuit = circuits.computeIfAbsent(name, k -> new CircuitState());
        Instant now = Instant.now();

        synchronized (circuit) {
            if (circuit.state == State.OPEN) {
                if (now.isAfter(circuit.lastStateChange.plusSeconds(resetTimeoutSeconds))) {
                    log.info("[{}] Circuit Breaker reset timeout expired. Transitioning to HALF_OPEN.", name);
                    circuit.state = State.HALF_OPEN;
                    circuit.lastStateChange = now;
                    circuit.halfOpenProbes = 0;
                } else {
                    log.debug("[{}] Circuit Breaker is OPEN. Executing fast fallback.", name);
                    return fallback.get();
                }
            }
        }

        try {
            T result = action.get();
            synchronized (circuit) {
                if (circuit.state == State.HALF_OPEN) {
                    circuit.halfOpenProbes++;
                    if (circuit.halfOpenProbes >= 3) {
                        log.info("[{}] Canary probes succeeded in HALF_OPEN. Transitioning to CLOSED.", name);
                        circuit.state = State.CLOSED;
                        circuit.failureCount = 0;
                        circuit.lastStateChange = Instant.now();
                    }
                } else if (circuit.state == State.CLOSED) {
                    circuit.failureCount = 0;
                }
            }
            return result;
        } catch (Exception e) {
            synchronized (circuit) {
                circuit.failureCount++;
                circuit.lastFailureTime = Instant.now();
                log.warn("[{}] Execution failure (count: {}/{}): {}", name, circuit.failureCount, failureThreshold, e.getMessage());

                if (circuit.state == State.HALF_OPEN || circuit.failureCount >= failureThreshold) {
                    log.error("[{}] Failure threshold breached! Tripping Circuit Breaker to OPEN.", name);
                    circuit.state = State.OPEN;
                    circuit.lastStateChange = Instant.now();
                }
            }
            return fallback.get();
        }
    }

    public State getState(String name) {
        CircuitState circuit = circuits.get(name);
        return circuit != null ? circuit.state : State.CLOSED;
    }

    public Instant getLastFailureTime(String name) {
        CircuitState circuit = circuits.get(name);
        return circuit != null ? circuit.lastFailureTime : null;
    }
}
