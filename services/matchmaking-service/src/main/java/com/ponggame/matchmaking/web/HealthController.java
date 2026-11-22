package com.ponggame.matchmaking.web;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class HealthController {

    private final StringRedisTemplate redisTemplate;

    public HealthController(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        try {
            String pong = redisTemplate.getConnectionFactory().getConnection().ping();
            return ResponseEntity.ok(Map.of("status", "UP", "service", "matchmaking-service", "redis", pong));
        } catch (Exception ex) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("status", "DOWN", "service", "matchmaking-service", "error", ex.getMessage()));
        }
    }
}
