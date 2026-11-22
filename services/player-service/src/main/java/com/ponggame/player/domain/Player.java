package com.ponggame.player.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "players")
public class Player {

    @Id
    private UUID id;

    @Column(nullable = false, length = 64)
    private String username;

    @Column(nullable = false)
    private int elo = 1000;

    @Column(name = "matches_played", nullable = false)
    private int matchesPlayed = 0;

    @Column(nullable = false)
    private int wins = 0;

    @Column(nullable = false)
    private int losses = 0;

    @Column(nullable = false)
    private int draws = 0;

    @Column(name = "total_points_scored", nullable = false)
    private int totalPointsScored = 0;

    @Column(name = "total_points_conceded", nullable = false)
    private int totalPointsConceded = 0;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected Player() {}

    public Player(UUID id, String username) {
        this.id = id;
        this.username = username;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void recordMatch(String result, int pointsScored, int pointsConceded) {
        this.matchesPlayed++;
        this.totalPointsScored += pointsScored;
        this.totalPointsConceded += pointsConceded;
        switch (result) {
            case "WIN" -> {
                this.wins++;
                this.elo += 25;
            }
            case "LOSS" -> {
                this.losses++;
                this.elo = Math.max(100, this.elo - 15);
            }
            default -> {
                this.draws++;
                this.elo += 5;
            }
        }
        this.updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; this.updatedAt = Instant.now(); }
    public int getElo() { return elo; }
    public int getMatchesPlayed() { return matchesPlayed; }
    public int getWins() { return wins; }
    public int getLosses() { return losses; }
    public int getDraws() { return draws; }
    public int getTotalPointsScored() { return totalPointsScored; }
    public int getTotalPointsConceded() { return totalPointsConceded; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
