package com.example.ecommerce.repository;

import java.util.UUID;

import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;

import com.example.ecommerce.model.OrderItem;

import reactor.core.publisher.Flux;

public interface OrderItemRepository extends ReactiveCrudRepository<OrderItem, UUID> {
    @Query("SELECT * FROM order_items WHERE order_id = :orderId ORDER BY created_at ASC")
    Flux<OrderItem> findByOrderId(UUID orderId);
}