package com.ponggame.player.repository;

import com.ponggame.player.domain.ProcessedMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface ProcessedMessageRepository extends JpaRepository<ProcessedMessage, UUID> {

    @Modifying
    @Query(value = """
            INSERT INTO processed_messages (message_id, message_type, correlation_id, processed_at)
            VALUES (:messageId, :messageType, :correlationId, NOW())
            ON CONFLICT (message_id) DO NOTHING
            """, nativeQuery = true)
    int tryMarkProcessed(
            @Param("messageId") UUID messageId,
            @Param("messageType") String messageType,
            @Param("correlationId") UUID correlationId);
}
