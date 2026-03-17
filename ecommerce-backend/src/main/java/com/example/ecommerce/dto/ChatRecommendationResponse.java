package com.example.ecommerce.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record ChatRecommendationResponse(
        UUID productId,
        String name,
        String description,
        String imageUrl,
        BigDecimal price,
        String reason,
        int salesCount,
        boolean withinBudget,
        List<String> tags
) {
}