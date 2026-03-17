package com.example.ecommerce.dto;

public record AiProviderOverviewResponse(
        String provider,
        String activeModelName,
        boolean fallback,
        String runtimeReason,
        int consecutiveFallbacks,
        boolean persisted,
        AiProviderEntryResponse deepseek,
        AiProviderEntryResponse siliconflow
) {
}