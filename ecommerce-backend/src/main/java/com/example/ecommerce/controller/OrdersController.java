package com.example.ecommerce.controller;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.ecommerce.dto.CreateOrderRequest;
import com.example.ecommerce.dto.OrderDetailResponse;
import com.example.ecommerce.dto.OrderSummaryResponse;
import com.example.ecommerce.service.OrderService;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/orders")
public class OrdersController {
    private final OrderService orderService;

    public OrdersController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping
    public Mono<OrderDetailResponse> create(@Validated @RequestBody CreateOrderRequest request) {
        return orderService.create(request);
    }

    @GetMapping("/health")
    public Mono<Map<String, Object>> health() {
        return Mono.just(Map.of(
                "service", "order-service",
                "status", "UP",
                "resource", "orders",
                "timestamp", Instant.now().toString()));
    }

    @GetMapping("/user/{userId}")
    public Flux<OrderSummaryResponse> listByUser(@PathVariable("userId") UUID userId) {
        return orderService.listByUser(userId);
    }

    @GetMapping("/{id}")
    public Mono<OrderDetailResponse> get(@PathVariable("id") UUID id) {
        return orderService.get(id);
    }
}
