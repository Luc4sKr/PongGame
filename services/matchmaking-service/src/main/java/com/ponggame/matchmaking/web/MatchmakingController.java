package com.ponggame.matchmaking.web;

import com.ponggame.matchmaking.security.AuthenticatedPlayer;
import com.ponggame.matchmaking.service.MatchmakingService;
import com.ponggame.matchmaking.service.MatchmakingService.ArenaReservationDto;
import com.ponggame.matchmaking.service.MatchmakingService.StatusDto;
import com.ponggame.matchmaking.service.MatchmakingService.TicketDto;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/matchmaking")
public class MatchmakingController {

    private final MatchmakingService matchmakingService;

    public MatchmakingController(MatchmakingService matchmakingService) {
        this.matchmakingService = matchmakingService;
    }

    /**
     * Async API (R3): returns 202 Accepted with ticketId immediately;
     * client polls GET /api/matchmaking/tickets/{ticketId}.
     */
    @PostMapping("/queue")
    public ResponseEntity<TicketDto> joinQueue(HttpServletRequest request) {
        AuthenticatedPlayer auth = (AuthenticatedPlayer) request.getAttribute(AuthenticatedPlayer.REQUEST_ATTR);
        TicketDto ticket = matchmakingService.enqueuePlayer(auth.playerId(), auth.username());
        return ResponseEntity.accepted().body(ticket);
    }

    @DeleteMapping("/queue")
    public ResponseEntity<Map<String, Object>> leaveQueue(HttpServletRequest request) {
        AuthenticatedPlayer auth = (AuthenticatedPlayer) request.getAttribute(AuthenticatedPlayer.REQUEST_ATTR);
        boolean cancelled = matchmakingService.cancelQueuedPlayer(auth.playerId());
        if (!cancelled) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("cancelled", false, "message", "No QUEUED ticket found for player"));
        }
        return ResponseEntity.ok(Map.of("cancelled", true));
    }

    @GetMapping("/tickets/{ticketId}")
    public ResponseEntity<TicketDto> getTicket(@PathVariable UUID ticketId) {
        return matchmakingService.getTicket(ticketId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Direct atomic arena slot reservation endpoint for concurrency control (R2 / F5).
     */
    @PostMapping("/arenas/reserve")
    public ResponseEntity<?> reserveArenaSlot(HttpServletRequest request) {
        AuthenticatedPlayer auth = (AuthenticatedPlayer) request.getAttribute(AuthenticatedPlayer.REQUEST_ATTR);
        return matchmakingService.tryReserveArenaSlot(auth.playerId())
                .<ResponseEntity<?>>map(res -> ResponseEntity.status(HttpStatus.CREATED).body(res))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(Map.of("error", "arena_capacity_full", "message", "No arena slots available")));
    }

    @DeleteMapping("/arenas/reserve/{reservationId}")
    public ResponseEntity<Map<String, Object>> releaseArenaSlot(@PathVariable UUID reservationId) {
        boolean released = matchmakingService.releaseArenaSlot(reservationId);
        return released
                ? ResponseEntity.ok(Map.of("released", true))
                : ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("released", false));
    }

    @GetMapping("/status")
    public ResponseEntity<StatusDto> status() {
        return ResponseEntity.ok(matchmakingService.getStatus());
    }
}
