package com.ponggame.matchmaking.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.GetPublicKeyRequest;

import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.concurrent.locks.ReentrantLock;

@Component
public class KmsPublicKeyProvider {

    private static final Logger log = LoggerFactory.getLogger(KmsPublicKeyProvider.class);

    private final KmsClient kmsClient;
    private final String kmsKeyId;
    private final long cacheTtlSeconds;
    private final ReentrantLock lock = new ReentrantLock();

    private volatile RSAPublicKey cachedKey;
    private volatile Instant expiresAt = Instant.EPOCH;

    public KmsPublicKeyProvider(
            KmsClient kmsClient,
            @Value("${app.kms.key-id:alias/ponggame-jwt-signing}") String kmsKeyId,
            @Value("${app.kms.cache-ttl-seconds:300}") long cacheTtlSeconds) {
        this.kmsClient = kmsClient;
        this.kmsKeyId = kmsKeyId;
        this.cacheTtlSeconds = cacheTtlSeconds;
    }

    public RSAPublicKey getPublicKey() {
        RSAPublicKey key = cachedKey;
        if (key != null && Instant.now().isBefore(expiresAt)) {
            return key;
        }
        return refresh(false);
    }

    public RSAPublicKey forceRefresh() {
        return refresh(true);
    }

    private RSAPublicKey refresh(boolean force) {
        lock.lock();
        try {
            if (!force && cachedKey != null && Instant.now().isBefore(expiresAt)) {
                return cachedKey;
            }
            var response = kmsClient.getPublicKey(GetPublicKeyRequest.builder()
                    .keyId(kmsKeyId)
                    .build());
            byte[] derBytes = response.publicKey().asByteArray();
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            RSAPublicKey rsaPublicKey = (RSAPublicKey) keyFactory.generatePublic(new X509EncodedKeySpec(derBytes));
            this.cachedKey = rsaPublicKey;
            this.expiresAt = Instant.now().plusSeconds(cacheTtlSeconds);
            log.info("Fetched and cached RSA public key from KMS key '{}' (TTL={}s)", kmsKeyId, cacheTtlSeconds);
            return rsaPublicKey;
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to fetch public key from KMS key '" + kmsKeyId + "'", ex);
        } finally {
            lock.unlock();
        }
    }
}
