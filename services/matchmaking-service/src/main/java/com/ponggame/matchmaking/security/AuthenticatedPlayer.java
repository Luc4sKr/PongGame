package com.ponggame.matchmaking.security;

import java.util.UUID;

public record AuthenticatedPlayer(UUID playerId, String username) {
    public static final String REQUEST_ATTR = "ponggame.authenticatedPlayer";
}
