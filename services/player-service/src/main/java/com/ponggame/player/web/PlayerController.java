package com.ponggame.player.web;

import com.ponggame.player.security.AuthenticatedPlayer;
import com.ponggame.player.service.PlayerStatsService;
import com.ponggame.player.service.PlayerStatsService.PlayerMatchDto;
import com.ponggame.player.service.PlayerStatsService.PlayerProfileDto;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/players")
public class PlayerController {

    private final PlayerStatsService playerStatsService;

    public PlayerController(PlayerStatsService playerStatsService) {
        this.playerStatsService = playerStatsService;
    }

    @GetMapping("/me")
    public ResponseEntity<PlayerProfileDto> me(HttpServletRequest request) {
        AuthenticatedPlayer auth = (AuthenticatedPlayer) request.getAttribute(AuthenticatedPlayer.REQUEST_ATTR);
        return ResponseEntity.ok(playerStatsService.getOrCreateProfile(auth.playerId(), auth.username()));
    }

    @GetMapping("/{playerId}")
    public ResponseEntity<PlayerProfileDto> getById(@PathVariable UUID playerId) {
        return playerStatsService.findProfileById(playerId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/me/matches")
    public ResponseEntity<List<PlayerMatchDto>> myMatches(
            HttpServletRequest request,
            @RequestParam(defaultValue = "20") int limit) {
        AuthenticatedPlayer auth = (AuthenticatedPlayer) request.getAttribute(AuthenticatedPlayer.REQUEST_ATTR);
        return ResponseEntity.ok(playerStatsService.getMatchHistory(auth.playerId(), limit));
    }

    @GetMapping("/{playerId}/matches")
    public ResponseEntity<List<PlayerMatchDto>> playerMatches(
            @PathVariable UUID playerId,
            @RequestParam(defaultValue = "20") int limit) {
        return ResponseEntity.ok(playerStatsService.getMatchHistory(playerId, limit));
    }

    @GetMapping("/leaderboard")
    public ResponseEntity<List<PlayerProfileDto>> leaderboard(@RequestParam(defaultValue = "20") int limit) {
        return ResponseEntity.ok(playerStatsService.getLeaderboard(limit));
    }
}
