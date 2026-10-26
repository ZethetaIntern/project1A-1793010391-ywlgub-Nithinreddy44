package com.payscale.httpe.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "merchant_settlements")
public class MerchantSettlement {

    @Id
    @Column(name = "settlement_id", nullable = false, updatable = false)
    private UUID settlementId;

    @Column(name = "batch_id", nullable = false, unique = true, length = 64)
    private String batchId;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Column(name = "transaction_count", nullable = false)
    private Integer transactionCount;

    @Column(name = "gross_amount", nullable = false, precision = 18, scale = 4)
    private BigDecimal grossAmount;

    @Column(name = "fee_amount", nullable = false, precision = 18, scale = 4)
    private BigDecimal feeAmount;

    @Column(name = "net_amount", nullable = false, precision = 18, scale = 4)
    private BigDecimal netAmount;

    @Column(name = "status", nullable = false, length = 32)
    private String status = "PENDING"; // PENDING, PROCESSING, SETTLED, FAILED

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "settled_at")
    private Instant settledAt;

    public MerchantSettlement() {}

    public MerchantSettlement(UUID settlementId, String batchId, UUID merchantId, Integer transactionCount, BigDecimal grossAmount, BigDecimal feeAmount, BigDecimal netAmount, String status) {
        this.settlementId = settlementId != null ? settlementId : UUID.randomUUID();
        this.batchId = batchId;
        this.merchantId = merchantId;
        this.transactionCount = transactionCount;
        this.grossAmount = grossAmount.setScale(4);
        this.feeAmount = feeAmount.setScale(4);
        this.netAmount = netAmount.setScale(4);
        this.status = status != null ? status : "PENDING";
        this.createdAt = Instant.now();
    }

    public UUID getSettlementId() { return settlementId; }
    public void setSettlementId(UUID settlementId) { this.settlementId = settlementId; }

    public String getBatchId() { return batchId; }
    public void setBatchId(String batchId) { this.batchId = batchId; }

    public UUID getMerchantId() { return merchantId; }
    public void setMerchantId(UUID merchantId) { this.merchantId = merchantId; }

    public Integer getTransactionCount() { return transactionCount; }
    public void setTransactionCount(Integer transactionCount) { this.transactionCount = transactionCount; }

    public BigDecimal getGrossAmount() { return grossAmount; }
    public void setGrossAmount(BigDecimal grossAmount) { this.grossAmount = grossAmount; }

    public BigDecimal getFeeAmount() { return feeAmount; }
    public void setFeeAmount(BigDecimal feeAmount) { this.feeAmount = feeAmount; }

    public BigDecimal getNetAmount() { return netAmount; }
    public void setNetAmount(BigDecimal netAmount) { this.netAmount = netAmount; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getSettledAt() { return settledAt; }
    public void setSettledAt(Instant settledAt) { this.settledAt = settledAt; }
}
