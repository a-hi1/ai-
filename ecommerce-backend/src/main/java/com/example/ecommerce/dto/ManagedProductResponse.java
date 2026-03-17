package com.example.ecommerce.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ManagedProductResponse(
        UUID id,
        String name,
        BigDecimal price,
        String dataSource,
        Instant createdAt
) {
}