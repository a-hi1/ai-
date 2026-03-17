package com.example.ecommerce.controller;

import java.time.Instant;
import java.util.UUID;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.ecommerce.dto.AddCartItemRequest;
import com.example.ecommerce.dto.CartItemResponse;
import com.example.ecommerce.dto.UpdateCartItemRequest;
import com.example.ecommerce.service.CartService;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/cart")
public class CartController {
    private final CartService cartService;

    public CartController(CartService cartService) {
        this.cartService = cartService;
    }

    @GetMapping("/{userId}")
    public Flux<CartItemResponse> list(@PathVariable("userId") UUID userId) {
        return cartService.list(userId);
    }

    @PostMapping("/items")
    public Mono<CartItemResponse> add(@Validated @RequestBody AddCartItemRequest request) {
        return cartService.add(request);
    }

    @PutMapping("/items/{itemId}")
    public Mono<CartItemResponse> updateQuantity(@PathVariable("itemId") UUID itemId, @Validated @RequestBody UpdateCartItemRequest request) {
        return cartService.updateQuantity(itemId, request.quantity());
    }

    @DeleteMapping("/items/{itemId}")
    public Mono<Void> delete(@PathVariable("itemId") UUID itemId) {
        return cartService.delete(itemId);
    }

    @DeleteMapping("/user/{userId}")
    public Mono<Void> clear(@PathVariable("userId") UUID userId) {
        return cartService.clearUserCart(userId);
    }
}
