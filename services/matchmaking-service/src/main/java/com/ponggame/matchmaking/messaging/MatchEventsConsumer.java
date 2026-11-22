package com.ponggame.matchmaking.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ponggame.matchmaking.service.MatchmakingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

@Component
public class MatchEventsConsumer {

    private static final Logger log = LoggerFactory.getLogger(MatchEventsConsumer.class);

    private final ObjectMapper objectMapper;
    private final MatchmakingService matchmakingService;

    public MatchEventsConsumer(ObjectMapper objectMapper, MatchmakingService matchmakingService) {
        this.objectMapper = objectMapper;
        this.matchmakingService = matchmakingService;
    }

    @RabbitListener(queues = "${app.rabbitmq.match-ready-queue:matchmaking.match.ready}")
    public void onMatchReady(Message rawMessage) throws Exception {
        EventEnvelope envelope = parseAndValidate(rawMessage, "match.ready");
        JsonNode p = envelope.payload();
        UUID matchId = requiredUuid(p, "matchId");
        String wsPath = requiredText(p, "wsPath");
        matchmakingService.handleMatchReady(envelope.messageId(), envelope.correlationId(), matchId, wsPath);
    }

    @RabbitListener(queues = "${app.rabbitmq.match-failed-queue:matchmaking.match.failed}")
    public void onMatchProvisioningFailed(Message rawMessage) throws Exception {
        EventEnvelope envelope = parseAndValidate(rawMessage, "match.provisioning_failed");
        JsonNode p = envelope.payload();
        UUID matchId = requiredUuid(p, "matchId");
        String reason = requiredText(p, "reason");
        matchmakingService.handleMatchProvisioningFailed(envelope.messageId(), envelope.correlationId(), matchId, reason);
    }

    @RabbitListener(queues = "${app.rabbitmq.match-finished-queue:matchmaking.match.finished}")
    public void onMatchFinished(Message rawMessage) throws Exception {
        EventEnvelope envelope = parseAndValidate(rawMessage, "match.finished");
        JsonNode p = envelope.payload();
        UUID matchId = requiredUuid(p, "matchId");
        String reason = requiredText(p, "reason");
        matchmakingService.handleMatchFinished(envelope.messageId(), envelope.correlationId(), matchId, reason);
    }

    private EventEnvelope parseAndValidate(Message rawMessage, String expectedType) throws Exception {
        String body = new String(rawMessage.getBody(), StandardCharsets.UTF_8);
        EventEnvelope envelope = objectMapper.readValue(body, EventEnvelope.class);
        if (envelope == null
                || envelope.messageId() == null
                || envelope.correlationId() == null
                || !expectedType.equals(envelope.type())
                || envelope.version() == null
                || envelope.version() < 1
                || envelope.payload() == null
                || !envelope.payload().isObject()) {
            log.error("Rejecting invalid {} envelope (will route to DLQ)", expectedType);
            throw new IllegalArgumentException("Invalid envelope for " + expectedType);
        }
        return envelope;
    }

    private static UUID requiredUuid(JsonNode node, String field) {
        JsonNode val = node.get(field);
        if (val == null || !val.isTextual() || val.asText().isBlank()) {
            throw new IllegalArgumentException("Missing required UUID field: " + field);
        }
        return UUID.fromString(val.asText());
    }

    private static String requiredText(JsonNode node, String field) {
        JsonNode val = node.get(field);
        if (val == null || !val.isTextual() || val.asText().isBlank()) {
            throw new IllegalArgumentException("Missing required text field: " + field);
        }
        return val.asText();
    }
}
