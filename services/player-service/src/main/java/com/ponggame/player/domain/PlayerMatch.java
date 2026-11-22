package com.ponggame.player.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "player_matches")
public class PlayerMatch {

    @Id
    private UUID id;

    @Column(name = "match_id", nullable = false)
    private UUID matchId;

    @Column(name = "player_id", nullable = false)
    private UUID playerId;

    @Column(name = "opponent_id", nullable = false)
    private UUID opponentId;

    @Column(name = "opponent_username", nullable = false, length = 64)
    private String opponentUsername;

    @Column(name = "player_score", nullable = false)
    private int playerScore;

    @Column(name = "opponent_score", nullable = false)
    private int opponentScore;

    @Column(nullable = false, length = 16)
    private String result;

    @Column(nullable = false, length = 32)
    private String reason;

    @Column(name = "duration_seconds", nullable = false)
    private int durationSeconds;

    @Column(name = "finished_at", nullable = false)
    private Instant finishedAt;

    protected PlayerMatch() {}

    public PlayerMatch(
            UUID matchId,
            UUID playerId,
            UUID opponentId,
            String opponentUsername,
            int playerScore,
            int opponentScore,
            String result,
            String reason,
            int durationSeconds,
            Instant finishedAt) {
        this.id = UUID.randomUUID();
        this.matchId = matchId;
        this.playerId = playerId;
        this.opponentId = opponentId;
        this.opponentUsername = opponentUsername;
        this.playerScore = playerScore;
        this.opponentScore = opponentScore;
        this.result = result;
        this.reason = reason;
        this.durationSeconds = durationSeconds;
        this.finishedAt = finishedAt;
    }

    public UUID getId() { return id; }
    public UUID getMatchId() { return matchId; }
    public UUID getPlayerId() { return playerId; }
    public UUID getOpponentId() { return opponentId; }
    public String getOpponentUsername() { return opponentUsername; }
    public int getPlayerScore() { return playerScore; }
    public int getOpponentScore() { return opponentScore; }
    public String getResult() { return result; }
    public String getReason() { return reason; }
    public int getDurationSeconds() { return durationSeconds; }
    public Instant getFinishedAt() { return finishedAt; }
}
