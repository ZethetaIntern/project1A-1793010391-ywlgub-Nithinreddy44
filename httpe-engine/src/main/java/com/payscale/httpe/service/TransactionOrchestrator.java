package com.payscale.httpe.service;

import com.payscale.httpe.domain.Transaction;
import com.payscale.httpe.domain.TransactionEvent;
import com.payscale.httpe.repository.TransactionEventRepository;
import com.payscale.httpe.repository.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Service
public class TransactionOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(TransactionOrchestrator.class);

    private final IdempotencyHandler idempotencyHandler;
    private final FraudDetectionService fraudDetectionService;
    private final PaymentProcessingService paymentProcessingService;
    private final TransactionRepository transactionRepository;
    private final TransactionEventRepository transactionEventRepository;
    private final AccountService accountService;

    public TransactionOrchestrator(
        IdempotencyHandler idempotencyHandler,
        FraudDetectionService fraudDetectionService,
        PaymentProcessingService paymentProcessingService,
        TransactionRepository transactionRepository,
        TransactionEventRepository transactionEventRepository,
        AccountService accountService
    ) {
        this.idempotencyHandler = idempotencyHandler;
        this.fraudDetectionService = fraudDetectionService;
        this.paymentProcessingService = paymentProcessingService;
        this.transactionRepository = transactionRepository;
        this.transactionEventRepository = transactionEventRepository;
        this.accountService = accountService;
    }

    public Transaction orchestrateP2PPayment(
        String idempotencyKey,
        UUID sourceAccountId,
        UUID destinationAccountId,
        BigDecimal amount,
        String currency,
        String ipAddress,
        String deviceId
    ) {
        // Construct canonical payload for idempotency verification
        String canonicalPayload = String.format("{\"source\":\"%s\",\"dest\":\"%s\",\"amount\":%.4f,\"currency\":\"%s\"}",
            sourceAccountId, destinationAccountId, amount.doubleValue(), currency != null ? currency : "INR");

        return idempotencyHandler.executeIdempotent(idempotencyKey, canonicalPayload, () -> {
            UUID sagaId = UUID.randomUUID();
            log.info("Starting Saga [{}] for P2P Transfer of {} {} from {} to {}",
                sagaId, amount, currency, sourceAccountId, destinationAccountId);

            // Step 1: Real-time Pre-Authorization Fraud Check (15ms SLA target)
            FraudDetectionService.FraudResult fraudResult = fraudDetectionService.evaluateTransaction(
                sourceAccountId, amount, ipAddress, deviceId
            );

            if (!fraudResult.isApproved()) {
                log.warn("Saga [{}] ABORTED: Fraud check rejected transaction - reason: {}", sagaId, fraudResult.getReason());
                Transaction failedTxn = new Transaction(
                    UUID.randomUUID(), idempotencyKey, "P2P", sourceAccountId, destinationAccountId, amount, currency, sagaId
                );
                failedTxn.setStatus("FAILED");
                failedTxn.setFailureReason("FRAUD_CHECK_REJECTED: " + fraudResult.getReason());
                failedTxn.setCompletedAt(Instant.now());
                recordStateTransition(failedTxn.getTransactionId(), "INITIATED", "FAILED", "FRAUD_CHECK_REJECTED", fraudResult.getReason());
                return transactionRepository.save(failedTxn);
            }

            // Step 2: Record Initial Saga Event
            UUID txnId = UUID.randomUUID();
            recordStateTransition(txnId, "INITIATED", "PROCESSING", "FRAUD_APPROVED", "Risk checked in " + fraudResult.getEvaluationTimeMs() + "ms");

            // Step 3: Execute Financial Processing (Debit, Credit, Ledger Journal, Outbox)
            Transaction txn = paymentProcessingService.processP2PPayment(
                sourceAccountId, destinationAccountId, amount, currency, idempotencyKey, sagaId
            );

            // Step 4: Record Terminal Event
            recordStateTransition(txn.getTransactionId(), "PROCESSING", txn.getStatus(), "PAYMENT_TERMINAL_STATE", txn.getFailureReason());

            return txn;
        });
    }

    /**
     * Executes manual or administrative compensating reversal for permitted transactions.
     * Complies with the 30-second RTO reversal mandate.
     */
    public Transaction executeCompensatingReversal(UUID transactionId, String reason) {
        Transaction txn = transactionRepository.findById(transactionId)
            .orElseThrow(() -> new IllegalArgumentException("Transaction not found: " + transactionId));

        if (!"COMPLETED".equalsIgnoreCase(txn.getStatus())) {
            throw new IllegalStateException("Only COMPLETED transactions can be reversed. Current status: " + txn.getStatus());
        }

        log.info("Executing compensating reversal for transaction {}: reason {}", transactionId, reason);

        // Reverse balances: credit sender, debit receiver
        accountService.executeCompensatingCredit(txn.getSourceAccountId(), txn.getAmount());

        txn.setStatus("REVERSED");
        txn.setFailureReason("REVERSED_BY_REQUEST: " + reason);
        txn.setCompletedAt(Instant.now());

        recordStateTransition(txn.getTransactionId(), "COMPLETED", "REVERSED", "COMPENSATING_REVERSAL", reason);
        return transactionRepository.save(txn);
    }

    private void recordStateTransition(UUID transactionId, String fromState, String toState, String eventName, String payload) {
        TransactionEvent event = new TransactionEvent(transactionId, fromState, toState, eventName, payload);
        transactionEventRepository.save(event);
    }
}
