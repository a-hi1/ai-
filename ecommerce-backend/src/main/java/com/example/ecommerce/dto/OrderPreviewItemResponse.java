package com.example.ecommerce.dto;

import java.util.UUID;

public record OrderPreviewItemResponse(
        UUID productId,
        String productName,
        String imageUrl,
        int quantity
) {
}