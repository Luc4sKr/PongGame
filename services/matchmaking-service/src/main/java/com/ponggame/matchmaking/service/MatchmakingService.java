package com.ponggame.matchmaking.service;

import com.ponggame.matchmaking.messaging.RedisOutboxPublisher;
import com.ponggame.matchmaking.observability.Telemetry;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

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
    private static final DefaultRedisScript<String> ENQUEUE_SCRIPT = redisScript("enqueue-player.lua", String.class);

    // Atomically pop two queued tickets and reserve 1 arena slot under maxConcurrentMatches
    @SuppressWarnings("rawtypes")
    private static final DefaultRedisScript<List> PAIR_SCRIPT = redisScript("pair-waiting-players.lua", List.class);

    // Atomically reserve 1 scarce arena slot (R2 / F5 concurrency control)
    private static final DefaultRedisScript<Long> RESERVE_SLOT_SCRIPT = redisScript("reserve-arena-slot.lua", Long.class);

    // Atomically release a direct arena reservation
    private static final DefaultRedisScript<Long> RELEASE_RESERVATION_SCRIPT = redisScript("release-arena-reservation.lua", Long.class);

    // Atomically release match slot once (idempotent against duplicate compensation/finish)
    private static final DefaultRedisScript<Long> RELEASE_MATCH_SLOT_SCRIPT = redisScript("release-match-slot.lua", Long.class);

    private static <T> DefaultRedisScript<T> redisScript(String name, Class<T> resultType) {
        var script = new DefaultRedisScript<T>();
        script.setLocation(new ClassPathResource("redis/" + name));
        script.setResultType(resultType);
        return script;
    }

    private final StringRedisTemplate redis;
    private final RedisOutboxPublisher outboxPublisher;
    private final int maxConcurrentMatches;
    private final int winningScore;
    private final long provisioningTimeoutMs;
    private final AtomicLong activeGauge = new AtomicLong();
    private final AtomicLong queueGauge = new AtomicLong();
    private final AtomicLong outboxGauge = new AtomicLong();

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
        var meter = GlobalOpenTelemetry.getMeter("ponggame/matchmaking");
        meter.gaugeBuilder("pong.matchmaking.active").ofLongs().buildWithCallback(o -> o.record(activeGauge.get()));
        meter.gaugeBuilder("pong.matchmaking.queued").ofLongs().buildWithCallback(o -> o.record(queueGauge.get()));
        meter.gaugeBuilder("pong.matchmaking.outbox.pending").ofLongs().buildWithCallback(o -> o.record(outboxGauge.get()));
        meter.gaugeBuilder("pong.matchmaking.capacity").ofLongs().buildWithCallback(o -> o.record(maxConcurrentMatches));
    }

    @Scheduled(fixedDelay = 3000)
    public void updateTelemetryGauges() {
        var status = getStatus();
        activeGauge.set(status.activeMatches());
        queueGauge.set(status.queuedPlayers());
        outboxGauge.set(status.pendingOutboxMessages());
    }

    public TicketDto enqueuePlayer(UUID playerId, String username) {
        UUID candidateTicketId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        String nowIso = Instant.now().toString();
        Map<String, String> traceContext = Telemetry.capture();

        String activeTicketId = redis.execute(
                ENQUEUE_SCRIPT,
                List.of(PLAYER_ACTIVE_PREFIX + playerId, TICKET_PREFIX + candidateTicketId, QUEUE_KEY),
                candidateTicketId.toString(),
                playerId.toString(),
                username,
                correlationId.toString(),
                nowIso,
                traceContext.getOrDefault("traceparent", ""),
                traceContext.getOrDefault("tracestate", ""));

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

            if (paired == null || paired.size() < 10) {
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

            var firstTicketContext = Map.of("traceparent", str(paired.get(6)), "tracestate", str(paired.get(7)));
            var secondTicketContext = Map.of("traceparent", str(paired.get(8)), "tracestate", str(paired.get(9)));
            try (var operation = Telemetry.startPairing(firstTicketContext, secondTicketContext)) {
                try {
                    Telemetry.annotate(matchId.toString(), null, correlationId.toString());
                    Map<String, String> matchContext = new HashMap<>();
                    Telemetry.capture().forEach((key, value) -> matchContext.put("otel." + key, value));
                    if (!matchContext.isEmpty()) {
                        redis.opsForHash().putAll(MATCH_PREFIX + matchId, matchContext);
                    }
                    outboxPublisher.enqueueEvent("match.created", "match.created", correlationId, payload);
                    log.info("Paired players into matchId={} (correlationId={})", matchId, correlationId);
                } catch (RuntimeException error) {
                    operation.error(error);
                    throw error;
                }
            }
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
            Telemetry.count("pong.arenas.reservations", "rejected");
            return Optional.empty();
        }
        Telemetry.count("pong.arenas.reservations", "created");
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
                Map<String, String> carrier = new HashMap<>();
                for (String key : List.of("traceparent", "tracestate")) {
                    if (match.containsKey("otel." + key)) carrier.put(key, str(match.get("otel." + key)));
                }
                try (var operation = Telemetry.start("saga.provisioning.timeout", Telemetry.restore(carrier))) {
                UUID matchId = UUID.fromString(matchIdStr);
                UUID corrId = UUID.fromString(str(match.get("correlationId")));
                Telemetry.annotate(matchIdStr, null, corrId.toString());
                operation.span.setStatus(StatusCode.ERROR, "PROVISIONING_TIMEOUT");
                log.warn("Provisioning timeout expired for matchId={}, triggering saga compensation", matchId);
                compensateMatch(matchId, corrId, "PROVISIONING_TIMEOUT");
                emitCancel(matchId, corrId, "PROVISIONING_TIMEOUT");
                }
            }
        }
    }

    private void compensateMatch(UUID matchId, UUID correlationId, String reason) {
        try (var operation = Telemetry.start("saga.compensate", Context.current())) {
        Telemetry.annotate(matchId.toString(), null, correlationId.toString());
        operation.span.setAttribute("pong.saga.reason", reason);
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
        Telemetry.count("pong.saga.compensations", reason);
        operation.span.setAttribute("pong.saga.compensated", true);
        }
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
