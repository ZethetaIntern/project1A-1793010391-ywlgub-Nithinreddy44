package com.payscale.httpe.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public class Dtos {

    public record CreateAccountRequest(
        UUID userId,
        String accountType,
        String currency,
        BigDecimal initialBalance,
        String tier
    ) {}

    public record AccountResponse(
        UUID accountId,
        UUID userId,
        String accountType,
        String currency,
        BigDecimal availableBalance,
        BigDecimal ledgerBalance,
        Long version,
        String status,
        String tier,
        Instant createdAt
    ) {}

    public record P2PPaymentRequest(
        UUID sourceAccountId,
        UUID destinationAccountId,
        BigDecimal amount,
        String currency,
        String notes
    ) {}

    public record MerchantPaymentRequest(
        UUID customerAccountId,
        UUID merchantAccountId,
        BigDecimal amount,
        String currency,
        String orderId
    ) {}

    public record TransactionResponse(
        UUID transactionId,
        String idempotencyKey,
        String transactionType,
        UUID sourceAccountId,
        UUID destinationAccountId,
        BigDecimal amount,
        String currency,
        String status,
        UUID sagaId,
        String failureReason,
        Instant createdAt,
        Instant completedAt
    ) {}

    public record PaginatedTransactionsResponse(
        List<TransactionResponse> data,
        String nextCursor,
        boolean hasMore
    ) {}

    public record ReversalRequest(
        String reason
    ) {}

    public record BatchSettlementRequest(
        String batchId,
        UUID settlementPoolAccountId,
        UUID merchantAccountId,
        UUID platformFeeAccountId,
        int transactionCount,
        BigDecimal grossAmount,
        BigDecimal mdrFeeRate
    ) {}

    public record SettlementBatchResponse(
        UUID settlementId,
        String batchId,
        int transactionCount,
        BigDecimal grossAmount,
        BigDecimal feeAmount,
        BigDecimal netAmount,
        String status,
        Instant settledAt
    ) {}

    public record ErrorResponse(
        String errorCode,
        String message,
        Instant timestamp,
        String correlationId
    ) {}
}
