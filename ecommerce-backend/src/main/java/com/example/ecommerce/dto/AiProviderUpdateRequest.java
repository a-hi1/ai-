package com.example.ecommerce.dto;

public record AiProviderUpdateRequest(
        String apiKey,
        String baseUrl,
        String modelName
) {
}