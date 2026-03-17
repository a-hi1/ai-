package com.example.ecommerce.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ProductInsightResponse(
        UUID productId,
        String name,
        String description,
        String imageUrl,
        BigDecimal price,
        String reason,
        String source,
        Instant viewedAt
) {
}