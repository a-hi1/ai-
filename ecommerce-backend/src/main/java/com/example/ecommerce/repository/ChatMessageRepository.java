package com.example.ecommerce.repository;

import java.util.UUID;

import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;

import com.example.ecommerce.model.ChatMessage;

import reactor.core.publisher.Flux;

public interface ChatMessageRepository extends ReactiveCrudRepository<ChatMessage, UUID> {
    @Query("SELECT * FROM chat_messages WHERE user_id = :userId ORDER BY created_at ASC")
    Flux<ChatMessage> findByUserId(UUID userId);

    /**
     * 按用户和会话查询消息，确保每个会话只使用自己的历史记录，防止跨会话污染
     */
    @Query("SELECT * FROM chat_messages WHERE user_id = :userId AND session_id = :sessionId ORDER BY created_at ASC")
    Flux<ChatMessage> findByUserIdAndSessionId(UUID userId, String sessionId);
}
