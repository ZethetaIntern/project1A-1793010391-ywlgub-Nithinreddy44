package com.payscale.httpe.api;

import com.payscale.httpe.api.dto.Dtos.*;
import com.payscale.httpe.domain.Transaction;
import com.payscale.httpe.service.TransactionOrchestrator;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {

    private final TransactionOrchestrator orchestrator;

    public PaymentController(TransactionOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @PostMapping("/p2p")
    public ResponseEntity<TransactionResponse> submitP2PPayment(
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @RequestBody P2PPaymentRequest req,
        HttpServletRequest httpRequest
    ) {
        String clientIp = httpRequest.getRemoteAddr();
        String deviceId = httpRequest.getHeader("User-Agent");

        Transaction txn = orchestrator.orchestrateP2PPayment(
            idempotencyKey,
            req.sourceAccountId(),
            req.destinationAccountId(),
            req.amount(),
            req.currency(),
            clientIp,
            deviceId
        );

        if ("FAILED".equals(txn.getStatus()) && txn.getFailureReason() != null && txn.getFailureReason().contains("INSUFFICIENT_FUNDS")) {
            return ResponseEntity.unprocessableEntity().body(toResponse(txn));
        }

        return ResponseEntity.ok(toResponse(txn));
    }

    @PostMapping("/merchant")
    public ResponseEntity<TransactionResponse> submitMerchantPayment(
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @RequestBody MerchantPaymentRequest req,
        HttpServletRequest httpRequest
    ) {
        String clientIp = httpRequest.getRemoteAddr();
        String deviceId = httpRequest.getHeader("User-Agent");

        Transaction txn = orchestrator.orchestrateP2PPayment(
            idempotencyKey,
            req.customerAccountId(),
            req.merchantAccountId(),
            req.amount(),
            req.currency(),
            clientIp,
            deviceId
        );

        return ResponseEntity.ok(toResponse(txn));
    }

    private TransactionResponse toResponse(Transaction t) {
        return new TransactionResponse(
            t.getTransactionId(),
            t.getIdempotencyKey(),
            t.getTransactionType(),
            t.getSourceAccountId(),
            t.getDestinationAccountId(),
            t.getAmount(),
            t.getCurrency(),
            t.getStatus(),
            t.getSagaId(),
            t.getFailureReason(),
            t.getCreatedAt(),
            t.getCompletedAt()
        );
    }
}
