package com.example.ecommerce.dto;

public record AiProviderEntryResponse(
        boolean hasApiKey,
        String maskedApiKey,
        String baseUrl,
        String modelName
) {
}