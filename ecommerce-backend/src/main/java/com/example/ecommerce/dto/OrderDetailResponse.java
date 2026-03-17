package com.example.ecommerce.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OrderDetailResponse(
        UUID id,
        UUID userId,
        String status,
        BigDecimal totalAmount,
        String paymentMethod,
        String gatewayTradeNo,
        Instant paidAt,
        Instant createdAt,
        List<OrderItemResponse> items
) {
}