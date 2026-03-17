package com.example.ecommerce.dto;

public record AiProviderConfigActionResponse(
        String message,
        AiProviderOverviewResponse config
) {
}