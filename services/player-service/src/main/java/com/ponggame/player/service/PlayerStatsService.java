package com.ponggame.player.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ponggame.player.domain.Player;
import com.ponggame.player.domain.PlayerMatch;
import com.ponggame.player.messaging.EventEnvelope;
import com.ponggame.player.messaging.MatchFinishedPayload;
import com.ponggame.player.repository.PlayerMatchRepository;
import com.ponggame.player.repository.PlayerRepository;
import com.ponggame.player.repository.ProcessedMessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class PlayerStatsService {

    private static final Logger log = LoggerFactory.getLogger(PlayerStatsService.class);
    private static final String CACHE_PREFIX = "player:profile:";

    private final PlayerRepository playerRepository;
    private final PlayerMatchRepository playerMatchRepository;
    private final ProcessedMessageRepository processedMessageRepository;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final long cacheTtlSeconds;

    public PlayerStatsService(
            PlayerRepository playerRepository,
            PlayerMatchRepository playerMatchRepository,
            ProcessedMessageRepository processedMessageRepository,
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            @Value("${app.cache.player-ttl-seconds:60}") long cacheTtlSeconds) {
        this.playerRepository = playerRepository;
        this.playerMatchRepository = playerMatchRepository;
        this.processedMessageRepository = processedMessageRepository;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.cacheTtlSeconds = cacheTtlSeconds;
    }

    @Transactional
    public PlayerProfileDto getOrCreateProfile(UUID playerId, String username) {
        Optional<PlayerProfileDto> cached = readFromCache(playerId);
        if (cached.isPresent()) {
            return cached.get();
        }
        Player player = playerRepository.findById(playerId)
                .orElseGet(() -> playerRepository.save(new Player(playerId, username)));
        PlayerProfileDto dto = PlayerProfileDto.from(player);
        writeToCache(dto);
        return dto;
    }

    @Transactional(readOnly = true)
    public Optional<PlayerProfileDto> findProfileById(UUID playerId) {
        Optional<PlayerProfileDto> cached = readFromCache(playerId);
        if (cached.isPresent()) {
            return cached;
        }
        return playerRepository.findById(playerId).map(player -> {
            PlayerProfileDto dto = PlayerProfileDto.from(player);
            writeToCache(dto);
            return dto;
        });
    }

    @Transactional(readOnly = true)
    public List<PlayerMatchDto> getMatchHistory(UUID playerId, int limit) {
        int clamped = Math.max(1, Math.min(limit, 100));
        return playerMatchRepository.findByPlayerIdOrderByFinishedAtDesc(playerId, PageRequest.of(0, clamped))
                .stream()
                .map(PlayerMatchDto::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<PlayerProfileDto> getLeaderboard(int limit) {
        int clamped = Math.max(1, Math.min(limit, 100));
        return playerRepository.findLeaderboard(PageRequest.of(0, clamped))
                .stream()
                .map(PlayerProfileDto::from)
                .toList();
    }

    /**
     * Records a completed match inside a single PostgreSQL transaction alongside the
     * processed_messages idempotency table (R1, F2, F3). Duplicate deliveries with the
     * same messageId are ignored with zero duplicate stat increments.
     */
    @Transactional
    public boolean recordMatchFinished(EventEnvelope envelope, MatchFinishedPayload payload) {
        int inserted = processedMessageRepository.tryMarkProcessed(
                envelope.messageId(),
                envelope.type(),
                envelope.correlationId());
        if (inserted == 0) {
            log.info("Ignoring duplicate messageId={} for matchId={} (already processed)",
                    envelope.messageId(), payload.matchId());
            return false;
        }

        Player p1 = playerRepository.findById(payload.player1Id())
                .orElseGet(() -> new Player(payload.player1Id(), payload.player1Username()));
        p1.setUsername(payload.player1Username());

        Player p2 = playerRepository.findById(payload.player2Id())
                .orElseGet(() -> new Player(payload.player2Id(), payload.player2Username()));
        p2.setUsername(payload.player2Username());

        String p1Result;
        String p2Result;
        if (payload.winnerId() == null) {
            p1Result = "DRAW";
            p2Result = "DRAW";
        } else if (payload.winnerId().equals(payload.player1Id())) {
            p1Result = "WIN";
            p2Result = "LOSS";
        } else {
            p1Result = "LOSS";
            p2Result = "WIN";
        }

        p1.recordMatch(p1Result, payload.player1Score(), payload.player2Score());
        p2.recordMatch(p2Result, payload.player2Score(), payload.player1Score());
        playerRepository.save(p1);
        playerRepository.save(p2);

        Instant finishedAt = payload.finishedAt() != null ? payload.finishedAt() : Instant.now();
        int duration = payload.durationSeconds() != null ? payload.durationSeconds() : 0;

        playerMatchRepository.save(new PlayerMatch(
                payload.matchId(),
                payload.player1Id(),
                payload.player2Id(),
                payload.player2Username(),
                payload.player1Score(),
                payload.player2Score(),
                p1Result,
                payload.reason(),
                duration,
                finishedAt));

        playerMatchRepository.save(new PlayerMatch(
                payload.matchId(),
                payload.player2Id(),
                payload.player1Id(),
                payload.player1Username(),
                payload.player2Score(),
                payload.player1Score(),
                p2Result,
                payload.reason(),
                duration,
                finishedAt));

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    evictCache(payload.player1Id());
                    evictCache(payload.player2Id());
                }
            });
        }

        log.info("Recorded matchId={} (messageId={}, correlationId={}): {} {} - {} {}",
                payload.matchId(), envelope.messageId(), envelope.correlationId(),
                payload.player1Username(), payload.player1Score(),
                payload.player2Score(), payload.player2Username());
        return true;
    }

    private Optional<PlayerProfileDto> readFromCache(UUID playerId) {
        try {
            String raw = redisTemplate.opsForValue().get(CACHE_PREFIX + playerId);
            if (raw == null || raw.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(raw, PlayerProfileDto.class));
        } catch (Exception ex) {
            log.debug("Redis cache read skipped for player {}: {}", playerId, ex.getMessage());
            return Optional.empty();
        }
    }

    private void writeToCache(PlayerProfileDto dto) {
        try {
            String json = objectMapper.writeValueAsString(dto);
            redisTemplate.opsForValue().set(CACHE_PREFIX + dto.playerId(), json, Duration.ofSeconds(cacheTtlSeconds));
        } catch (Exception ex) {
            log.debug("Redis cache write skipped for player {}: {}", dto.playerId(), ex.getMessage());
        }
    }

    private void evictCache(UUID playerId) {
        try {
            redisTemplate.delete(CACHE_PREFIX + playerId);
        } catch (Exception ex) {
            log.debug("Redis cache evict skipped for player {}: {}", playerId, ex.getMessage());
        }
    }

    public record PlayerProfileDto(
            UUID playerId,
            String username,
            int elo,
            int matchesPlayed,
            int wins,
            int losses,
            int draws,
            double winRate,
            int totalPointsScored,
            int totalPointsConceded,
            Instant updatedAt
    ) {
        public static PlayerProfileDto from(Player p) {
            double rate = p.getMatchesPlayed() == 0
                    ? 0.0
                    : Math.round((p.getWins() * 10000.0) / p.getMatchesPlayed()) / 100.0;
            return new PlayerProfileDto(
                    p.getId(),
                    p.getUsername(),
                    p.getElo(),
                    p.getMatchesPlayed(),
                    p.getWins(),
                    p.getLosses(),
                    p.getDraws(),
                    rate,
                    p.getTotalPointsScored(),
                    p.getTotalPointsConceded(),
                    p.getUpdatedAt());
        }
    }

    public record PlayerMatchDto(
            UUID matchId,
            UUID opponentId,
            String opponentUsername,
            int playerScore,
            int opponentScore,
            String result,
            String reason,
            int durationSeconds,
            Instant finishedAt
    ) {
        public static PlayerMatchDto from(PlayerMatch m) {
            return new PlayerMatchDto(
                    m.getMatchId(),
                    m.getOpponentId(),
                    m.getOpponentUsername(),
                    m.getPlayerScore(),
                    m.getOpponentScore(),
                    m.getResult(),
                    m.getReason(),
                    m.getDurationSeconds(),
                    m.getFinishedAt());
        }
    }
}
