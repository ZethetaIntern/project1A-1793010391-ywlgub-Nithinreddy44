package com.payscale.httpe.api;

import com.payscale.httpe.api.dto.Dtos.ErrorResponse;
import com.payscale.httpe.service.IdempotencyHandler.IdempotencyConflictException;
import com.payscale.httpe.service.IdempotencyHandler.TransactionInProgressException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.UUID;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<ErrorResponse> handleIdempotencyConflict(IdempotencyConflictException ex) {
        String corrId = UUID.randomUUID().toString();
        log.warn("[{}] Idempotency conflict: {}", corrId, ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse(
            "IDEMPOTENCY_CONFLICT",
            ex.getMessage(),
            Instant.now(),
            corrId
        ));
    }

    @ExceptionHandler(TransactionInProgressException.class)
    public ResponseEntity<ErrorResponse> handleInProgress(TransactionInProgressException ex) {
        String corrId = UUID.randomUUID().toString();
        log.warn("[{}] Transaction in progress: {}", corrId, ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse(
            "TRANSACTION_IN_PROGRESS",
            ex.getMessage(),
            Instant.now(),
            corrId
        ));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleBadRequest(IllegalArgumentException ex) {
        String corrId = UUID.randomUUID().toString();
        log.warn("[{}] Bad request: {}", corrId, ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(
            "INVALID_ARGUMENT",
            ex.getMessage(),
            Instant.now(),
            corrId
        ));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorResponse> handleUnprocessable(IllegalStateException ex) {
        String corrId = UUID.randomUUID().toString();
        log.warn("[{}] Business rule violation: {}", corrId, ex.getMessage());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(new ErrorResponse(
            "BUSINESS_RULE_VIOLATION",
            ex.getMessage(),
            Instant.now(),
            corrId
        ));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGenericException(Exception ex) {
        String corrId = UUID.randomUUID().toString();
        // Never expose internal stack traces or database connection details to API clients
        log.error("[{}] Internal unhandled server error: {}", corrId, ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ErrorResponse(
            "INTERNAL_SERVER_ERROR",
            "An unexpected error occurred during processing. Please contact support with correlation ID: " + corrId,
            Instant.now(),
            corrId
        ));
    }
}
