package com.ponggame.player.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ponggame.player.service.PlayerStatsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
public class MatchFinishedConsumer {

    private static final Logger log = LoggerFactory.getLogger(MatchFinishedConsumer.class);

    private final ObjectMapper objectMapper;
    private final PlayerStatsService playerStatsService;

    public MatchFinishedConsumer(ObjectMapper objectMapper, PlayerStatsService playerStatsService) {
        this.objectMapper = objectMapper;
        this.playerStatsService = playerStatsService;
    }

    @RabbitListener(queues = "${app.rabbitmq.match-finished-queue:player.match.finished}")
    public void onMatchFinished(Message rawMessage) throws Exception {
        String body = new String(rawMessage.getBody(), StandardCharsets.UTF_8);
        EventEnvelope envelope = objectMapper.readValue(body, EventEnvelope.class);

        validateEnvelope(envelope);
        MatchFinishedPayload payload = objectMapper.treeToValue(envelope.payload(), MatchFinishedPayload.class);
        validatePayload(payload);

        playerStatsService.recordMatchFinished(envelope, payload);
    }

    private void validateEnvelope(EventEnvelope envelope) {
        if (envelope == null
                || envelope.messageId() == null
                || envelope.correlationId() == null
                || !"match.finished".equals(envelope.type())
                || envelope.version() == null
                || envelope.version() < 1
                || envelope.payload() == null
                || !envelope.payload().isObject()) {
            log.error("Rejecting invalid match.finished envelope (will route to DLQ after retries)");
            throw new IllegalArgumentException("Invalid match.finished envelope");
        }
    }

    private void validatePayload(MatchFinishedPayload payload) {
        if (payload == null
                || payload.matchId() == null
                || payload.player1Id() == null
                || payload.player2Id() == null
                || payload.player1Username() == null || payload.player1Username().isBlank()
                || payload.player2Username() == null || payload.player2Username().isBlank()
                || payload.player1Score() == null || payload.player1Score() < 0
                || payload.player2Score() == null || payload.player2Score() < 0
                || payload.reason() == null || payload.reason().isBlank()) {
            log.error("Rejecting invalid match.finished payload (will route to DLQ after retries)");
            throw new IllegalArgumentException("Invalid match.finished payload");
        }
    }
}
