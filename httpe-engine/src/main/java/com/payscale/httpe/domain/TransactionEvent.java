package com.payscale.httpe.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "transaction_events", indexes = {
    @Index(name = "idx_txn_events_txn_id", columnList = "transactionId"),
    @Index(name = "idx_txn_events_created", columnList = "createdAt")
})
public class TransactionEvent {

    @Id
    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Column(name = "transaction_id", nullable = false)
    private UUID transactionId;

    @Column(name = "from_state", nullable = false, length = 32)
    private String fromState;

    @Column(name = "to_state", nullable = false, length = 32)
    private String toState;

    @Column(name = "event_name", nullable = false, length = 64)
    private String eventName;

    @Column(name = "payload", columnDefinition = "TEXT")
    private String payload = "{}";

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public TransactionEvent() {}

    public TransactionEvent(UUID transactionId, String fromState, String toState, String eventName, String payload) {
        this.eventId = UUID.randomUUID();
        this.transactionId = transactionId;
        this.fromState = fromState;
        this.toState = toState;
        this.eventName = eventName;
        this.payload = payload != null ? payload : "{}";
        this.createdAt = Instant.now();
    }

    public UUID getEventId() { return eventId; }
    public void setEventId(UUID eventId) { this.eventId = eventId; }

    public UUID getTransactionId() { return transactionId; }
    public void setTransactionId(UUID transactionId) { this.transactionId = transactionId; }

    public String getFromState() { return fromState; }
    public void setFromState(String fromState) { this.fromState = fromState; }

    public String getToState() { return toState; }
    public void setToState(String toState) { this.toState = toState; }

    public String getEventName() { return eventName; }
    public void setEventName(String eventName) { this.eventName = eventName; }

    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
