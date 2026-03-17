package com.example.ecommerce.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record RecordProductViewRequest(
        @NotNull UUID userId,
        @NotNull UUID productId,
        @NotBlank String source,
        String reason
) {
}