package com.ponggame.matchmaking.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Redis-backed outbox publisher for RabbitMQ (R6 / F6).
 * Outbound events are durably appended to Redis first and then published to RabbitMQ.
 * If RabbitMQ is temporarily unreachable, events accumulate in Redis and are automatically
 * drained in FIFO order as soon as the broker reconnects.
 */
@Component
public class RedisOutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(RedisOutboxPublisher.class);
    public static final String OUTBOX_KEY = "mm:outbox";

    private final StringRedisTemplate redis;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final String eventsExchange;
    private final AtomicBoolean draining = new AtomicBoolean(false);

    public RedisOutboxPublisher(
            StringRedisTemplate redis,
            RabbitTemplate rabbitTemplate,
            ObjectMapper objectMapper,
            @Value("${app.rabbitmq.events-exchange:pong.events}") String eventsExchange) {
        this.redis = redis;
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
        this.eventsExchange = eventsExchange;
    }

    public UUID enqueueEvent(String routingKey, String eventType, UUID correlationId, Object payload) {
        UUID messageId = UUID.randomUUID();
        try {
            JsonNode payloadNode = objectMapper.valueToTree(payload);
            EventEnvelope envelope = new EventEnvelope(
                    messageId,
                    correlationId,
                    eventType,
                    1,
                    Instant.now(),
                    payloadNode);
            String envelopeJson = objectMapper.writeValueAsString(envelope);
            OutboxRecord record = new OutboxRecord(routingKey, envelopeJson);
            redis.opsForList().rightPush(OUTBOX_KEY, objectMapper.writeValueAsString(record));
            drainOutbox();
            return messageId;
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to enqueue outbox event " + eventType, ex);
        }
    }

    public long pendingCount() {
        Long size = redis.opsForList().size(OUTBOX_KEY);
        return size != null ? size : 0L;
    }

    @Scheduled(fixedDelay = 1000)
    public void drainOutbox() {
        if (!draining.compareAndSet(false, true)) {
            return;
        }
        try {
            while (true) {
                String raw = redis.opsForList().index(OUTBOX_KEY, 0);
                if (raw == null) {
                    return;
                }
                OutboxRecord record = objectMapper.readValue(raw, OutboxRecord.class);
                Message message = MessageBuilder
                        .withBody(record.bodyJson().getBytes(StandardCharsets.UTF_8))
                        .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                        .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                        .build();
                rabbitTemplate.send(eventsExchange, record.routingKey(), message);
                redis.opsForList().leftPop(OUTBOX_KEY);
                log.info("Published outbox event to exchange={} routingKey={}", eventsExchange, record.routingKey());
            }
        } catch (Exception ex) {
            log.warn("RabbitMQ unavailable while draining outbox (will retry automatically): {}", ex.getMessage());
        } finally {
            draining.set(false);
        }
    }

    public record OutboxRecord(String routingKey, String bodyJson) {}
}
