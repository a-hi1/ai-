package com.example.ecommerce.repository;

import java.util.UUID;

import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;

import com.example.ecommerce.model.CartItem;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface CartItemRepository extends ReactiveCrudRepository<CartItem, UUID> {
    @Query("SELECT * FROM cart_items WHERE user_id = :userId ORDER BY created_at DESC")
    Flux<CartItem> findByUserId(UUID userId);

    Mono<CartItem> findByUserIdAndProductId(UUID userId, UUID productId);
}
