package com.ponggame.player.repository;

import com.ponggame.player.domain.PlayerMatch;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PlayerMatchRepository extends JpaRepository<PlayerMatch, UUID> {
    List<PlayerMatch> findByPlayerIdOrderByFinishedAtDesc(UUID playerId, Pageable pageable);
}
