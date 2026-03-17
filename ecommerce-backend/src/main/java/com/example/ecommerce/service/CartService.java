package com.example.ecommerce.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import com.example.ecommerce.dto.AddCartItemRequest;
import com.example.ecommerce.dto.CartItemResponse;
import com.example.ecommerce.model.CartItem;
import com.example.ecommerce.repository.CartItemRepository;
import com.example.ecommerce.repository.ProductRepository;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
public class CartService {
    private final CartItemRepository cartItemRepository;
    private final ProductRepository productRepository;
    private final R2dbcEntityTemplate entityTemplate;

    public CartService(CartItemRepository cartItemRepository, ProductRepository productRepository, R2dbcEntityTemplate entityTemplate) {
        this.cartItemRepository = cartItemRepository;
        this.productRepository = productRepository;
        this.entityTemplate = entityTemplate;
    }

    public Flux<CartItemResponse> list(UUID userId) {
        return cartItemRepository.findByUserId(userId).flatMap(this::toResponse);
    }

    public Mono<List<CartItemResponse>> listSnapshot(UUID userId) {
        return list(userId).collectList();
    }

    public Mono<CartItemResponse> add(AddCartItemRequest request) {
        return cartItemRepository.findByUserIdAndProductId(request.userId(), request.productId())
                .flatMap(existing -> {
                    existing.setQuantity(existing.getQuantity() + request.quantity());
                    return cartItemRepository.save(existing);
                })
                .switchIfEmpty(Mono.defer(() -> {
                    CartItem item = new CartItem(UUID.randomUUID(), request.userId(), request.productId(), request.quantity(), Instant.now());
                    return entityTemplate.insert(CartItem.class).using(item);
                }))
                .flatMap(this::toResponse);
    }

    public Mono<CartItemResponse> updateQuantity(UUID itemId, int quantity) {
        return cartItemRepository.findById(itemId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Cart item not found")))
                .flatMap(item -> {
                    item.setQuantity(quantity);
                    return cartItemRepository.save(item);
                })
                .flatMap(this::toResponse);
    }

    public Mono<Void> delete(UUID itemId) {
        return cartItemRepository.deleteById(itemId);
    }

    public Mono<Void> clearUserCart(UUID userId) {
        return cartItemRepository.findByUserId(userId)
                .flatMap(cartItemRepository::delete)
                .then();
    }

    private Mono<CartItemResponse> toResponse(CartItem item) {
        return productRepository.findById(item.getProductId())
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found")))
                .map(product -> {
                    BigDecimal lineTotal = product.getPrice().multiply(BigDecimal.valueOf(item.getQuantity()));
                    return new CartItemResponse(
                            item.getId(),
                            product.getId(),
                            product.getName(),
                            product.getDescription(),
                            product.getImageUrl(),
                            product.getPrice(),
                            item.getQuantity(),
                            lineTotal);
                });
    }
}