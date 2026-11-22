package com.ponggame.matchmaking.service;

import com.ponggame.matchmaking.messaging.RedisOutboxPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class MatchmakingService {

    private static final Logger log = LoggerFactory.getLogger(MatchmakingService.class);

    private static final String QUEUE_KEY = "mm:queue";
    private static final String ARENA_ACTIVE_KEY = "mm:arena:active_count";
    private static final String DEADLINES_ZSET = "mm:provisioning:deadlines";
    private static final String PLAYER_ACTIVE_PREFIX = "mm:player:active:";
    private static final String TICKET_PREFIX = "mm:ticket:";
    private static final String MATCH_PREFIX = "mm:match:";
    private static final String RESERVATION_PREFIX = "mm:reservation:";
    private static final String PROCESSED_PREFIX = "mm:processed:";

    // Atomically enroll player into FIFO queue if not already queued/in-match
    private static final DefaultRedisScript<String> ENQUEUE_SCRIPT = new DefaultRedisScript<>("""
            local existing = redis.call('GET', KEYS[1])
            if existing then
                return existing
            end
            redis.call('SET', KEYS[1], ARGV[1], 'EX', 3600)
            redis.call('HSET', KEYS[2],
                'ticketId', ARGV[1],
                'playerId', ARGV[2],
                'username', ARGV[3],
                'status', 'QUEUED',
                'correlationId', ARGV[4],
                'matchId', '',
                'wsPath', '',
                'reason', '',
                'updatedAt', ARGV[5])
            redis.call('EXPIRE', KEYS[2], 7200)
            redis.call('RPUSH', KEYS[3], ARGV[1])
            return ARGV[1]
            """, String.class);

    // Atomically pop two queued tickets and reserve 1 arena slot under maxConcurrentMatches
    @SuppressWarnings("rawtypes")
    private static final DefaultRedisScript<List> PAIR_SCRIPT = new DefaultRedisScript<>("""
            local maxSlots = tonumber(ARGV[1])
            local active = tonumber(redis.call('GET', KEYS[2]) or '0')
            if active >= maxSlots then
                return {}
            end

            local function popValidTicket()
                while true do
                    local tid = redis.call('LPOP', KEYS[1])
                    if not tid then
                        return nil
                    end
                    local st = redis.call('HGET', 'mm:ticket:' .. tid, 'status')
                    if st == 'QUEUED' then
                        return tid
                    end
                end
            end

            local t1 = popValidTicket()
            if not t1 then
                return {}
            end
            local t2 = popValidTicket()
            if not t2 then
                redis.call('LPUSH', KEYS[1], t1)
                return {}
            end

            local p1Id = redis.call('HGET', 'mm:ticket:' .. t1, 'playerId')
            local p1User = redis.call('HGET', 'mm:ticket:' .. t1, 'username')
            local p2Id = redis.call('HGET', 'mm:ticket:' .. t2, 'playerId')
            local p2User = redis.call('HGET', 'mm:ticket:' .. t2, 'username')

            if p1Id == p2Id then
                redis.call('LPUSH', KEYS[1], t1)
                return {}
            end

            redis.call('INCR', KEYS[2])

            local matchId = ARGV[2]
            local corrId = ARGV[3]
            local nowIso = ARGV[4]
            local deadlineMs = ARGV[5]

            redis.call('HSET', 'mm:ticket:' .. t1, 'status', 'PROVISIONING', 'matchId', matchId, 'correlationId', corrId, 'updatedAt', nowIso)
            redis.call('HSET', 'mm:ticket:' .. t2, 'status', 'PROVISIONING', 'matchId', matchId, 'correlationId', corrId, 'updatedAt', nowIso)
            redis.call('HSET', 'mm:match:' .. matchId,
                'matchId', matchId,
                'status', 'PROVISIONING',
                'correlationId', corrId,
                'player1Id', p1Id,
                'player1Username', p1User,
                'ticket1Id', t1,
                'player2Id', p2Id,
                'player2Username', p2User,
                'ticket2Id', t2,
                'deadlineMs', deadlineMs,
                'slotReleased', '0',
                'updatedAt', nowIso)
            redis.call('EXPIRE', 'mm:match:' .. matchId, 7200)
            redis.call('ZADD', KEYS[3], tonumber(deadlineMs), matchId)

            return {t1, p1Id, p1User, t2, p2Id, p2User}
            """, List.class);

    // Atomically reserve 1 scarce arena slot (R2 / F5 concurrency control)
    private static final DefaultRedisScript<Long> RESERVE_SLOT_SCRIPT = new DefaultRedisScript<>("""
            local limit = tonumber(ARGV[1])
            local current = tonumber(redis.call('GET', KEYS[1]) or '0')
            if current >= limit then
                return 0
            end
            local nextVal = redis.call('INCR', KEYS[1])
            redis.call('SET', KEYS[2], ARGV[2], 'EX', 3600)
            return nextVal
            """, Long.class);

    // Atomically release a direct arena reservation
    private static final DefaultRedisScript<Long> RELEASE_RESERVATION_SCRIPT = new DefaultRedisScript<>("""
            local exists = redis.call('DEL', KEYS[2])
            if exists == 1 then
                local current = tonumber(redis.call('GET', KEYS[1]) or '0')
                if current > 0 then
                    redis.call('DECR', KEYS[1])
                end
                return 1
            end
            return 0
            """, Long.class);

    // Atomically release match slot once (idempotent against duplicate compensation/finish)
    private static final DefaultRedisScript<Long> RELEASE_MATCH_SLOT_SCRIPT = new DefaultRedisScript<>("""
            local released = redis.call('HGET', KEYS[1], 'slotReleased')
            if released == '1' then
                return 0
            end
            redis.call('HSET', KEYS[1], 'slotReleased', '1')
            local current = tonumber(redis.call('GET', KEYS[2]) or '0')
            if current > 0 then
                redis.call('DECR', KEYS[2])
            end
            return 1
            """, Long.class);

    private final StringRedisTemplate redis;
    private final RedisOutboxPublisher outboxPublisher;
    private final int maxConcurrentMatches;
    private final int winningScore;
    private final long provisioningTimeoutMs;

    public MatchmakingService(
            StringRedisTemplate redis,
            RedisOutboxPublisher outboxPublisher,
            @Value("${app.matchmaking.max-concurrent-matches:10}") int maxConcurrentMatches,
            @Value("${app.matchmaking.winning-score:5}") int winningScore,
            @Value("${app.matchmaking.provisioning-timeout-ms:10000}") long provisioningTimeoutMs) {
        this.redis = redis;
        this.outboxPublisher = outboxPublisher;
        this.maxConcurrentMatches = maxConcurrentMatches;
        this.winningScore = winningScore;
        this.provisioningTimeoutMs = provisioningTimeoutMs;
    }

    public TicketDto enqueuePlayer(UUID playerId, String username) {
        UUID candidateTicketId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        String nowIso = Instant.now().toString();

        String activeTicketId = redis.execute(
                ENQUEUE_SCRIPT,
                List.of(PLAYER_ACTIVE_PREFIX + playerId, TICKET_PREFIX + candidateTicketId, QUEUE_KEY),
                candidateTicketId.toString(),
                playerId.toString(),
                username,
                correlationId.toString(),
                nowIso);

        tryPairWaitingPlayers();

        return getTicket(UUID.fromString(activeTicketId))
                .orElseThrow(() -> new IllegalStateException("Ticket missing immediately after enqueue"));
    }

    @Scheduled(fixedDelay = 1000)
    public void tryPairWaitingPlayers() {
        while (true) {
            UUID matchId = UUID.randomUUID();
            UUID correlationId = UUID.randomUUID();
            Instant now = Instant.now();
            Instant deadline = now.plusMillis(provisioningTimeoutMs);

            @SuppressWarnings("unchecked")
            List<Object> paired = redis.execute(
                    PAIR_SCRIPT,
                    List.of(QUEUE_KEY, ARENA_ACTIVE_KEY, DEADLINES_ZSET),
                    String.valueOf(maxConcurrentMatches),
                    matchId.toString(),
                    correlationId.toString(),
                    now.toString(),
                    String.valueOf(deadline.toEpochMilli()));

            if (paired == null || paired.size() < 6) {
                return;
            }

            String ticket1Id = String.valueOf(paired.get(0));
            String p1Id = String.valueOf(paired.get(1));
            String p1User = String.valueOf(paired.get(2));
            String ticket2Id = String.valueOf(paired.get(3));
            String p2Id = String.valueOf(paired.get(4));
            String p2User = String.valueOf(paired.get(5));

            Map<String, Object> payload = Map.of(
                    "matchId", matchId.toString(),
                    "player1", Map.of("playerId", p1Id, "username", p1User, "ticketId", ticket1Id),
                    "player2", Map.of("playerId", p2Id, "username", p2User, "ticketId", ticket2Id),
                    "winningScore", winningScore,
                    "provisionDeadline", deadline.toString());

            outboxPublisher.enqueueEvent("match.created", "match.created", correlationId, payload);
            log.info("Paired {} vs {} into matchId={} (correlationId={})", p1User, p2User, matchId, correlationId);
        }
    }

    public boolean cancelQueuedPlayer(UUID playerId) {
        String ticketId = redis.opsForValue().get(PLAYER_ACTIVE_PREFIX + playerId);
        if (ticketId == null || ticketId.isBlank()) {
            return false;
        }
        String ticketKey = TICKET_PREFIX + ticketId;
        Object status = redis.opsForHash().get(ticketKey, "status");
        if (!"QUEUED".equals(status)) {
            return false;
        }
        redis.opsForHash().put(ticketKey, "status", "CANCELLED");
        redis.opsForHash().put(ticketKey, "reason", "CANCELLED_BY_PLAYER");
        redis.opsForHash().put(ticketKey, "updatedAt", Instant.now().toString());
        redis.delete(PLAYER_ACTIVE_PREFIX + playerId);
        redis.opsForList().remove(QUEUE_KEY, 0, ticketId);
        return true;
    }

    public Optional<TicketDto> getTicket(UUID ticketId) {
        Map<Object, Object> entries = redis.opsForHash().entries(TICKET_PREFIX + ticketId);
        if (entries.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new TicketDto(
                str(entries.get("ticketId")),
                str(entries.get("playerId")),
                str(entries.get("username")),
                str(entries.get("status")),
                emptyToNull(str(entries.get("matchId"))),
                emptyToNull(str(entries.get("wsPath"))),
                str(entries.get("correlationId")),
                emptyToNull(str(entries.get("reason"))),
                str(entries.get("updatedAt"))));
    }

    public Optional<ArenaReservationDto> tryReserveArenaSlot(UUID playerId) {
        UUID reservationId = UUID.randomUUID();
        Long activeAfter = redis.execute(
                RESERVE_SLOT_SCRIPT,
                List.of(ARENA_ACTIVE_KEY, RESERVATION_PREFIX + reservationId),
                String.valueOf(maxConcurrentMatches),
                playerId.toString());
        if (activeAfter == null || activeAfter <= 0) {
            return Optional.empty();
        }
        return Optional.of(new ArenaReservationDto(
                reservationId.toString(),
                playerId.toString(),
                activeAfter.intValue(),
                maxConcurrentMatches));
    }

    public boolean releaseArenaSlot(UUID reservationId) {
        Long released = redis.execute(
                RELEASE_RESERVATION_SCRIPT,
                List.of(ARENA_ACTIVE_KEY, RESERVATION_PREFIX + reservationId));
        return released != null && released == 1L;
    }

    public boolean markMessageProcessed(UUID messageId) {
        Boolean added = redis.opsForValue().setIfAbsent(
                PROCESSED_PREFIX + messageId,
                Instant.now().toString(),
                Duration.ofDays(7));
        return Boolean.TRUE.equals(added);
    }

    public void handleMatchReady(UUID messageId, UUID correlationId, UUID matchId, String wsPath) {
        if (!markMessageProcessed(messageId)) {
            log.info("Ignoring duplicate match.ready messageId={} matchId={}", messageId, matchId);
            return;
        }

        String matchKey = MATCH_PREFIX + matchId;
        Map<Object, Object> match = redis.opsForHash().entries(matchKey);
        if (match.isEmpty()) {
            log.warn("Received match.ready for unknown matchId={}, emitting match.cancel", matchId);
            emitCancel(matchId, correlationId, "UNKNOWN_MATCH");
            return;
        }

        String status = str(match.get("status"));
        long deadlineMs = Long.parseLong(match.getOrDefault("deadlineMs", "0").toString());

        // F7 Late Response Compensation: match already timed out / compensated!
        if (!"PROVISIONING".equals(status) || (deadlineMs > 0 && Instant.now().toEpochMilli() > deadlineMs)) {
            log.warn("Late match.ready arrived for matchId={} (status={}); compensating via match.cancel", matchId, status);
            if ("PROVISIONING".equals(status)) {
                compensateMatch(matchId, correlationId, "LATE_PROVISIONING_RESPONSE");
            } else {
                emitCancel(matchId, correlationId, "LATE_PROVISIONING_RESPONSE");
            }
            return;
        }

        String nowIso = Instant.now().toString();
        redis.opsForZSet().remove(DEADLINES_ZSET, matchId.toString());
        redis.opsForHash().put(matchKey, "status", "READY");
        redis.opsForHash().put(matchKey, "wsPath", wsPath);
        redis.opsForHash().put(matchKey, "updatedAt", nowIso);

        updateTicket(str(match.get("ticket1Id")), "READY", wsPath, "", nowIso);
        updateTicket(str(match.get("ticket2Id")), "READY", wsPath, "", nowIso);
        log.info("Match {} is READY at {} (correlationId={})", matchId, wsPath, correlationId);
    }

    public void handleMatchProvisioningFailed(UUID messageId, UUID correlationId, UUID matchId, String reason) {
        if (!markMessageProcessed(messageId)) {
            log.info("Ignoring duplicate match.provisioning_failed messageId={} matchId={}", messageId, matchId);
            return;
        }
        compensateMatch(matchId, correlationId, reason);
    }

    public void handleMatchFinished(UUID messageId, UUID correlationId, UUID matchId, String reason) {
        if (!markMessageProcessed(messageId)) {
            log.info("Ignoring duplicate match.finished messageId={} matchId={}", messageId, matchId);
            return;
        }

        String matchKey = MATCH_PREFIX + matchId;
        Map<Object, Object> match = redis.opsForHash().entries(matchKey);
        if (match.isEmpty()) {
            log.info("Received match.finished for unknown/already-cleaned matchId={}", matchId);
            return;
        }

        String nowIso = Instant.now().toString();
        redis.opsForZSet().remove(DEADLINES_ZSET, matchId.toString());
        redis.opsForHash().put(matchKey, "status", "FINISHED");
        redis.opsForHash().put(matchKey, "reason", reason);
        redis.opsForHash().put(matchKey, "updatedAt", nowIso);

        updateTicket(str(match.get("ticket1Id")), "FINISHED", str(match.get("wsPath")), reason, nowIso);
        updateTicket(str(match.get("ticket2Id")), "FINISHED", str(match.get("wsPath")), reason, nowIso);

        releasePlayerLock(str(match.get("player1Id")), str(match.get("ticket1Id")));
        releasePlayerLock(str(match.get("player2Id")), str(match.get("ticket2Id")));
        redis.execute(RELEASE_MATCH_SLOT_SCRIPT, List.of(matchKey, ARENA_ACTIVE_KEY));

        log.info("Released arena slot and player locks after match.finished matchId={} (correlationId={})",
                matchId, correlationId);
    }

    /**
     * Watches for provisioning deadlines expiring (F7: slow dependency exceeding timeout).
     * Compensates the saga, frees the arena slot & player locks, and publishes match.cancel.
     */
    @Scheduled(fixedDelay = 1000)
    public void expireTimedOutProvisioningMatches() {
        long nowMs = Instant.now().toEpochMilli();
        Set<String> expired = redis.opsForZSet().rangeByScore(DEADLINES_ZSET, 0, nowMs);
        if (expired == null || expired.isEmpty()) {
            return;
        }
        for (String matchIdStr : expired) {
            Long removed = redis.opsForZSet().remove(DEADLINES_ZSET, matchIdStr);
            if (removed == null || removed == 0) {
                continue;
            }
            Map<Object, Object> match = redis.opsForHash().entries(MATCH_PREFIX + matchIdStr);
            if ("PROVISIONING".equals(match.get("status"))) {
                UUID matchId = UUID.fromString(matchIdStr);
                UUID corrId = UUID.fromString(str(match.get("correlationId")));
                log.warn("Provisioning timeout expired for matchId={}, triggering saga compensation", matchId);
                compensateMatch(matchId, corrId, "PROVISIONING_TIMEOUT");
                emitCancel(matchId, corrId, "PROVISIONING_TIMEOUT");
            }
        }
    }

    private void compensateMatch(UUID matchId, UUID correlationId, String reason) {
        String matchKey = MATCH_PREFIX + matchId;
        Map<Object, Object> match = redis.opsForHash().entries(matchKey);
        if (match.isEmpty()) {
            return;
        }
        String currentStatus = str(match.get("status"));
        if ("COMPENSATED".equals(currentStatus) || "FINISHED".equals(currentStatus)) {
            return;
        }

        String nowIso = Instant.now().toString();
        redis.opsForZSet().remove(DEADLINES_ZSET, matchId.toString());
        redis.opsForHash().put(matchKey, "status", "COMPENSATED");
        redis.opsForHash().put(matchKey, "reason", reason);
        redis.opsForHash().put(matchKey, "updatedAt", nowIso);

        updateTicket(str(match.get("ticket1Id")), "COMPENSATED", "", reason, nowIso);
        updateTicket(str(match.get("ticket2Id")), "COMPENSATED", "", reason, nowIso);

        releasePlayerLock(str(match.get("player1Id")), str(match.get("ticket1Id")));
        releasePlayerLock(str(match.get("player2Id")), str(match.get("ticket2Id")));
        redis.execute(RELEASE_MATCH_SLOT_SCRIPT, List.of(matchKey, ARENA_ACTIVE_KEY));

        log.info("Saga compensated for matchId={} reason={} (correlationId={}): arena slot & player locks released",
                matchId, reason, correlationId);
    }

    private void emitCancel(UUID matchId, UUID correlationId, String reason) {
        outboxPublisher.enqueueEvent("match.cancel", "match.cancel", correlationId, Map.of(
                "matchId", matchId.toString(),
                "reason", reason));
    }

    private void updateTicket(String ticketId, String status, String wsPath, String reason, String updatedAt) {
        if (ticketId == null || ticketId.isBlank()) {
            return;
        }
        String key = TICKET_PREFIX + ticketId;
        redis.opsForHash().put(key, "status", status);
        if (wsPath != null) {
            redis.opsForHash().put(key, "wsPath", wsPath);
        }
        if (reason != null) {
            redis.opsForHash().put(key, "reason", reason);
        }
        redis.opsForHash().put(key, "updatedAt", updatedAt);
    }

    private void releasePlayerLock(String playerId, String expectedTicketId) {
        if (playerId == null || playerId.isBlank()) {
            return;
        }
        String key = PLAYER_ACTIVE_PREFIX + playerId;
        String current = redis.opsForValue().get(key);
        if (expectedTicketId == null || expectedTicketId.equals(current)) {
            redis.delete(key);
        }
    }

    public StatusDto getStatus() {
        Long queueLen = redis.opsForList().size(QUEUE_KEY);
        String activeRaw = redis.opsForValue().get(ARENA_ACTIVE_KEY);
        int active = activeRaw != null ? Integer.parseInt(activeRaw) : 0;
        int available = Math.max(0, maxConcurrentMatches - active);
        return new StatusDto(
                queueLen != null ? queueLen : 0L,
                active,
                maxConcurrentMatches,
                available,
                outboxPublisher.pendingCount());
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }

    private static String emptyToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    public record TicketDto(
            String ticketId,
            String playerId,
            String username,
            String status,
            String matchId,
            String wsPath,
            String correlationId,
            String reason,
            String updatedAt
    ) {}

    public record ArenaReservationDto(
            String reservationId,
            String playerId,
            int activeSlots,
            int maxConcurrentMatches
    ) {}

    public record StatusDto(
            long queuedPlayers,
            int activeMatches,
            int maxConcurrentMatches,
            int availableSlots,
            long pendingOutboxMessages
    ) {}
}
