package com.payscale.httpe.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "transactions", indexes = {
    @Index(name = "uq_txns_idempotency_key", columnList = "idempotencyKey", unique = true),
    @Index(name = "idx_txns_source_created", columnList = "sourceAccountId, createdAt"),
    @Index(name = "idx_txns_dest_created", columnList = "destinationAccountId, createdAt"),
    @Index(name = "idx_txns_saga_id", columnList = "sagaId")
})
public class Transaction {

    @Id
    @Column(name = "transaction_id", nullable = false, updatable = false)
    private UUID transactionId;

    @Column(name = "idempotency_key", nullable = false, length = 128, unique = true)
    private String idempotencyKey;

    @Column(name = "transaction_type", nullable = false, length = 32)
    private String transactionType;

    @Column(name = "source_account_id")
    private UUID sourceAccountId;

    @Column(name = "destination_account_id")
    private UUID destinationAccountId;

    @Column(name = "amount", nullable = false, precision = 18, scale = 4)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency = "INR";

    @Column(name = "status", nullable = false, length = 32)
    private String status = "INITIATED";

    @Column(name = "saga_id", nullable = false)
    private UUID sagaId;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "metadata", columnDefinition = "TEXT")
    private String metadata = "{}";

    public Transaction() {}

    public Transaction(UUID transactionId, String idempotencyKey, String transactionType, UUID sourceAccountId, UUID destinationAccountId, BigDecimal amount, String currency, UUID sagaId) {
        this.transactionId = transactionId != null ? transactionId : UUID.randomUUID();
        this.idempotencyKey = idempotencyKey;
        this.transactionType = transactionType;
        this.sourceAccountId = sourceAccountId;
        this.destinationAccountId = destinationAccountId;
        this.amount = amount != null ? amount.setScale(4) : BigDecimal.ZERO.setScale(4);
        this.currency = currency != null ? currency : "INR";
        this.sagaId = sagaId != null ? sagaId : UUID.randomUUID();
        this.status = "INITIATED";
        this.createdAt = Instant.now();
    }

    public UUID getTransactionId() { return transactionId; }
    public void setTransactionId(UUID transactionId) { this.transactionId = transactionId; }

    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }

    public String getTransactionType() { return transactionType; }
    public void setTransactionType(String transactionType) { this.transactionType = transactionType; }

    public UUID getSourceAccountId() { return sourceAccountId; }
    public void setSourceAccountId(UUID sourceAccountId) { this.sourceAccountId = sourceAccountId; }

    public UUID getDestinationAccountId() { return destinationAccountId; }
    public void setDestinationAccountId(UUID destinationAccountId) { this.destinationAccountId = destinationAccountId; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount.setScale(4); }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public UUID getSagaId() { return sagaId; }
    public void setSagaId(UUID sagaId) { this.sagaId = sagaId; }

    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String failureReason) { this.failureReason = failureReason; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }

    public String getMetadata() { return metadata; }
    public void setMetadata(String metadata) { this.metadata = metadata; }
}
