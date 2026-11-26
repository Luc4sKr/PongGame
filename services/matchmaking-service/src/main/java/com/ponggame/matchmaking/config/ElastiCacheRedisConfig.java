package com.ponggame.matchmaking.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import software.amazon.awssdk.services.elasticache.ElastiCacheClient;
import software.amazon.awssdk.services.elasticache.model.DescribeCacheClustersRequest;

@Configuration
public class ElastiCacheRedisConfig {

    private static final Logger log = LoggerFactory.getLogger(ElastiCacheRedisConfig.class);

    @Value("${app.elasticache.cluster-id:ponggame-redis}")
    private String clusterId;

    @Bean
    @Primary
    public LettuceConnectionFactory redisConnectionFactory(ElastiCacheClient elastiCacheClient) {
        Endpoint resolved = discoverEndpoint(elastiCacheClient);
        log.info("Discovered ElastiCache cluster '{}' endpoint {}:{}", clusterId, resolved.host(), resolved.port());
        return new LettuceConnectionFactory(new RedisStandaloneConfiguration(resolved.host(), resolved.port()));
    }

    @Bean
    public StringRedisTemplate stringRedisTemplate(LettuceConnectionFactory factory) {
        return new StringRedisTemplate(factory);
    }

    private Endpoint discoverEndpoint(ElastiCacheClient client) {
        Exception lastError = null;
        for (int attempt = 1; attempt <= 20; attempt++) {
            try {
                var resp = client.describeCacheClusters(DescribeCacheClustersRequest.builder()
                        .cacheClusterId(clusterId)
                        .showCacheNodeInfo(true)
                        .build());
                if (resp.hasCacheClusters() && !resp.cacheClusters().isEmpty()) {
                    var cluster = resp.cacheClusters().get(0);
                    if (cluster.hasCacheNodes() && !cluster.cacheNodes().isEmpty()) {
                        var ep = cluster.cacheNodes().get(0).endpoint();
                        if (ep != null && ep.address() != null && !ep.address().isBlank()) {
                            int p = ep.port() != null ? ep.port() : 6379;
                            return new Endpoint(ep.address(), p);
                        }
                    }
                }
            } catch (Exception ex) {
                lastError = ex;
                log.warn("Waiting for ElastiCache cluster '{}' (attempt {}/20): {}", clusterId, attempt, ex.getMessage());
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while discovering ElastiCache cluster '" + clusterId + "'", ie);
            }
        }
        throw new IllegalStateException(
                "Failed to discover ElastiCache cluster '" + clusterId + "' after 20 attempts"
                + (lastError != null ? ": " + lastError.getMessage() : " (cluster not found or has no nodes)"));
    }

    private record Endpoint(String host, int port) {}
}
