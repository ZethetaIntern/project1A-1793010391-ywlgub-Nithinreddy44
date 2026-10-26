package com.payscale.httpe.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

@Component
public class IdempotencyHandler {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyHandler.class);
    private static final Duration RETENTION_WINDOW = Duration.ofHours(24); // Mandatory 24h per spec

    private final StringRedisTemplate redisTemplate;
    // In-memory fallback map if Redis is temporarily unreachable
    private final Map<String, CachedRecord> localFallbackCache = new ConcurrentHashMap<>();

    public IdempotencyHandler(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public static class CachedRecord {
        private final String payloadHash;
        private final String status; // IN_FLIGHT, COMPLETED, FAILED
        private final Object responseBody;

        public CachedRecord(String payloadHash, String status, Object responseBody) {
            this.payloadHash = payloadHash;
            this.status = status;
            this.responseBody = responseBody;
        }

        public String getPayloadHash() { return payloadHash; }
        public String getStatus() { return status; }
        public Object getResponseBody() { return responseBody; }
    }

    public String computePayloadHash(String payload) {
        if (payload == null) payload = "";
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    public <T> T executeIdempotent(String idempotencyKey, String canonicalPayload, Supplier<T> businessLogic) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key header is mandatory");
        }

        String payloadHash = computePayloadHash(canonicalPayload);
        String redisKey = "idempotency:" + idempotencyKey;

        // Step 1: Check existing record in Redis or local cache
        CachedRecord existing = getCachedRecord(redisKey);
        if (existing != null) {
            // Validate payload hash match
            if (!existing.getPayloadHash().equals(payloadHash)) {
                log.error("Idempotency conflict for key {}: existing hash {} vs new hash {}",
                    idempotencyKey, existing.getPayloadHash(), payloadHash);
                throw new IdempotencyConflictException(
                    "Idempotency key already exists with a different request payload (HTTP 409 Conflict)"
                );
            }

            if ("IN_FLIGHT".equals(existing.getStatus())) {
                log.warn("Concurrent request in-flight for idempotency key {}", idempotencyKey);
                throw new TransactionInProgressException(
                    "A transaction with this idempotency key is currently processing. Please retry shortly."
                );
            }

            // Exact match & completed -> Return cached response
            log.info("Idempotent HIT for key {}: returning cached response with zero duplicate financial effect.", idempotencyKey);
            @SuppressWarnings("unchecked")
            T cached = (T) existing.getResponseBody();
            return cached;
        }

        // Step 2: Atomic Reservation
        reserveKey(redisKey, payloadHash);

        // Step 3: Execute Business Logic
        try {
            T result = businessLogic.get();
            // Cache terminal completion
            saveCompleted(redisKey, payloadHash, result);
            return result;
        } catch (Exception e) {
            markFailed(redisKey, payloadHash);
            throw e;
        }
    }

    private CachedRecord getCachedRecord(String key) {
        try {
            if (redisTemplate != null) {
                String raw = redisTemplate.opsForValue().get(key);
                if (raw != null) {
                    String[] parts = raw.split("::", 3);
                    return new CachedRecord(parts[0], parts[1], parts.length > 2 ? parts[2] : null);
                }
            }
        } catch (Exception e) {
            log.warn("Redis read failure; falling back to in-memory idempotency store: {}", e.getMessage());
        }
        return localFallbackCache.get(key);
    }

    private void reserveKey(String key, String payloadHash) {
        String val = payloadHash + "::IN_FLIGHT";
        boolean reserved = false;
        try {
            if (redisTemplate != null) {
                Boolean setOk = redisTemplate.opsForValue().setIfAbsent(key, val, RETENTION_WINDOW);
                reserved = Boolean.TRUE.equals(setOk);
            }
        } catch (Exception e) {
            log.warn("Redis set failure; reserving in local fallback store: {}", e.getMessage());
        }
        if (!reserved) {
            localFallbackCache.put(key, new CachedRecord(payloadHash, "IN_FLIGHT", null));
        }
    }

    private void saveCompleted(String key, String payloadHash, Object response) {
        String val = payloadHash + "::COMPLETED::" + (response != null ? response.toString() : "");
        try {
            if (redisTemplate != null) {
                redisTemplate.opsForValue().set(key, val, RETENTION_WINDOW);
            }
        } catch (Exception ignored) {}
        localFallbackCache.put(key, new CachedRecord(payloadHash, "COMPLETED", response));
    }

    private void markFailed(String key, String payloadHash) {
        String val = payloadHash + "::FAILED";
        try {
            if (redisTemplate != null) {
                redisTemplate.opsForValue().set(key, val, Duration.ofMinutes(10));
            }
        } catch (Exception ignored) {}
        localFallbackCache.put(key, new CachedRecord(payloadHash, "FAILED", null));
    }

    public static class IdempotencyConflictException extends RuntimeException {
        public IdempotencyConflictException(String message) { super(message); }
    }

    public static class TransactionInProgressException extends RuntimeException {
        public TransactionInProgressException(String message) { super(message); }
    }
}
