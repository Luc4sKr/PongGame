package com.ponggame.player.messaging;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

public record EventEnvelope(
        UUID messageId,
        UUID correlationId,
        String type,
        Integer version,
        Instant occurredAt,
        JsonNode payload
) {}
