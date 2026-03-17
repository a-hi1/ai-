package com.example.ecommerce.repository;

import java.util.UUID;

import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;

import com.example.ecommerce.model.ChatMessage;

import reactor.core.publisher.Flux;

public interface ChatMessageRepository extends ReactiveCrudRepository<ChatMessage, UUID> {
    @Query("SELECT * FROM chat_messages WHERE user_id = :userId ORDER BY created_at ASC")
    Flux<ChatMessage> findByUserId(UUID userId);
}
