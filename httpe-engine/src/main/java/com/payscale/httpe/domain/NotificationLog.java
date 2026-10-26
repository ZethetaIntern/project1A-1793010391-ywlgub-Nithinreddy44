package com.payscale.httpe.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "notification_log", indexes = {
    @Index(name = "idx_notify_status_created", columnList = "status, createdAt")
})
public class NotificationLog {

    @Id
    @Column(name = "notification_id", nullable = false, updatable = false)
    private UUID notificationId;

    @Column(name = "transaction_id", nullable = false)
    private UUID transactionId;

    @Column(name = "channel", nullable = false, length = 32)
    private String channel; // WEBSOCKET, WEBHOOK, SMS, PUSH

    @Column(name = "recipient", nullable = false, length = 255)
    private String recipient;

    @Column(name = "status", nullable = false, length = 32)
    private String status = "PENDING"; // PENDING, DELIVERED, FAILED, RETRYING

    @Column(name = "attempts", nullable = false)
    private Integer attempts = 0;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    public NotificationLog() {}

    public NotificationLog(UUID transactionId, String channel, String recipient) {
        this.notificationId = UUID.randomUUID();
        this.transactionId = transactionId;
        this.channel = channel;
        this.recipient = recipient;
        this.status = "PENDING";
        this.attempts = 0;
        this.createdAt = Instant.now();
    }

    public UUID getNotificationId() { return notificationId; }
    public void setNotificationId(UUID notificationId) { this.notificationId = notificationId; }

    public UUID getTransactionId() { return transactionId; }
    public void setTransactionId(UUID transactionId) { this.transactionId = transactionId; }

    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }

    public String getRecipient() { return recipient; }
    public void setRecipient(String recipient) { this.recipient = recipient; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Integer getAttempts() { return attempts; }
    public void setAttempts(Integer attempts) { this.attempts = attempts; }

    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getDeliveredAt() { return deliveredAt; }
    public void setDeliveredAt(Instant deliveredAt) { this.deliveredAt = deliveredAt; }
}
