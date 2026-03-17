package com.example.ecommerce.repository;

import java.util.UUID;

import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;

import com.example.ecommerce.model.Product;

import reactor.core.publisher.Flux;

public interface ProductRepository extends ReactiveCrudRepository<Product, UUID> {
    @Query("SELECT * FROM products WHERE LOWER(name) LIKE LOWER(CONCAT('%', :q, '%')) OR LOWER(COALESCE(description, '')) LIKE LOWER(CONCAT('%', :q, '%')) OR LOWER(COALESCE(tags, '')) LIKE LOWER(CONCAT('%', :q, '%')) OR LOWER(COALESCE(category, '')) LIKE LOWER(CONCAT('%', :q, '%')) OR LOWER(COALESCE(specs, '')) LIKE LOWER(CONCAT('%', :q, '%')) OR LOWER(COALESCE(selling_points, '')) LIKE LOWER(CONCAT('%', :q, '%'))")
    Flux<Product> searchByKeyword(String q);
}
