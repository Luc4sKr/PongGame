package com.ponggame.player.repository;

import com.ponggame.player.domain.Player;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface PlayerRepository extends JpaRepository<Player, UUID> {
    @Query("SELECT p FROM Player p ORDER BY p.elo DESC, p.wins DESC, p.username ASC")
    List<Player> findLeaderboard(Pageable pageable);
}
