package com.ponggame.player.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "processed_messages")
public class ProcessedMessage {

    @Id
    @Column(name = "message_id")
    private UUID messageId;

    @Column(name = "message_type", nullable = false, length = 100)
    private String messageType;

    @Column(name = "correlation_id")
    private UUID correlationId;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    protected ProcessedMessage() {}

    public ProcessedMessage(UUID messageId, String messageType, UUID correlationId) {
        this.messageId = messageId;
        this.messageType = messageType;
        this.correlationId = correlationId;
        this.processedAt = Instant.now();
    }

    public UUID getMessageId() { return messageId; }
    public String getMessageType() { return messageType; }
    public UUID getCorrelationId() { return correlationId; }
    public Instant getProcessedAt() { return processedAt; }
}
