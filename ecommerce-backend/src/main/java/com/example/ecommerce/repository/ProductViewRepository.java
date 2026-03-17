package com.example.ecommerce.repository;

import java.util.UUID;

import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;

import com.example.ecommerce.model.ProductView;

import reactor.core.publisher.Flux;

public interface ProductViewRepository extends ReactiveCrudRepository<ProductView, UUID> {
    @Query("SELECT * FROM product_views WHERE user_id = :userId ORDER BY created_at DESC LIMIT 12")
    Flux<ProductView> findRecentByUserId(UUID userId);
}