package com.ponggame.player.messaging;

import java.time.Instant;
import java.util.UUID;

public record MatchFinishedPayload(
        UUID matchId,
        UUID player1Id,
        String player1Username,
        Integer player1Score,
        UUID player2Id,
        String player2Username,
        Integer player2Score,
        UUID winnerId,
        String reason,
        Integer durationSeconds,
        Instant finishedAt
) {}
