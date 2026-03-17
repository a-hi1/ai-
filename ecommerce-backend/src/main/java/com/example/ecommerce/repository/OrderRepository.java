package com.example.ecommerce.repository;

import java.util.UUID;

import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;

import com.example.ecommerce.model.Order;

import reactor.core.publisher.Flux;

public interface OrderRepository extends ReactiveCrudRepository<Order, UUID> {
    @Query("SELECT * FROM orders WHERE user_id = :userId ORDER BY created_at DESC")
    Flux<Order> findByUserId(UUID userId);
}
