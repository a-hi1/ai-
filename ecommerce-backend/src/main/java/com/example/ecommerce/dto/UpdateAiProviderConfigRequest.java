package com.example.ecommerce.dto;

public record UpdateAiProviderConfigRequest(
        String provider,
        AiProviderUpdateRequest deepseek,
        AiProviderUpdateRequest siliconflow
) {
}