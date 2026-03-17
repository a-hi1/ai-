package com.example.ecommerce.dto;

import java.time.Instant;
import java.util.List;

public record ChatResponse(
        String reply,
        Instant timestamp,
        List<ChatRecommendationResponse> recommendations,
        List<ChatRecommendationResponse> relatedRecommendations,
        List<ChatInsightResponse> insights,
        String detectedIntent,
        String budgetSummary,
        boolean fallback
) {
}