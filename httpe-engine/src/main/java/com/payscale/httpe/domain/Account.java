package com.payscale.httpe.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "accounts", indexes = {
    @Index(name = "idx_accounts_user_id", columnList = "userId"),
    @Index(name = "idx_accounts_shard_key", columnList = "shardKey")
})
public class Account {

    @Id
    @Column(name = "account_id", nullable = false, updatable = false)
    private UUID accountId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "account_type", nullable = false, length = 32)
    private String accountType;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency = "INR";

    @Column(name = "available_balance", nullable = false, precision = 18, scale = 4)
    private BigDecimal availableBalance = BigDecimal.ZERO.setScale(4);

    @Column(name = "ledger_balance", nullable = false, precision = 18, scale = 4)
    private BigDecimal ledgerBalance = BigDecimal.ZERO.setScale(4);

    @Version
    @Column(name = "version", nullable = false)
    private Long version = 1L;

    @Column(name = "status", nullable = false, length = 32)
    private String status = "ACTIVE";

    @Column(name = "tier", nullable = false, length = 32)
    private String tier = "BASIC";

    @Column(name = "shard_key", nullable = false)
    private Integer shardKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public Account() {}

    public Account(UUID accountId, UUID userId, String accountType, String currency, BigDecimal initialBalance, String tier, Integer shardKey) {
        this.accountId = accountId;
        this.userId = userId;
        this.accountType = accountType;
        this.currency = currency != null ? currency : "INR";
        this.availableBalance = initialBalance != null ? initialBalance.setScale(4) : BigDecimal.ZERO.setScale(4);
        this.ledgerBalance = this.availableBalance;
        this.tier = tier != null ? tier : "BASIC";
        this.shardKey = shardKey != null ? shardKey : Math.abs(accountId.hashCode()) % 4;
        this.status = "ACTIVE";
        this.version = 1L;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public UUID getAccountId() { return accountId; }
    public void setAccountId(UUID accountId) { this.accountId = accountId; }

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }

    public String getAccountType() { return accountType; }
    public void setAccountType(String accountType) { this.accountType = accountType; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public BigDecimal getAvailableBalance() { return availableBalance; }
    public void setAvailableBalance(BigDecimal availableBalance) { this.availableBalance = availableBalance.setScale(4); }

    public BigDecimal getLedgerBalance() { return ledgerBalance; }
    public void setLedgerBalance(BigDecimal ledgerBalance) { this.ledgerBalance = ledgerBalance.setScale(4); }

    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getTier() { return tier; }
    public void setTier(String tier) { this.tier = tier; }

    public Integer getShardKey() { return shardKey; }
    public void setShardKey(Integer shardKey) { this.shardKey = shardKey; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
