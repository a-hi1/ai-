package com.example.ecommerce.dto;

import java.util.List;

public record ChatAdvicePayload(
        String reply,
        List<ChatRecommendationResponse> recommendations,
        List<ChatRecommendationResponse> relatedRecommendations,
        List<ChatInsightResponse> insights,
        String detectedIntent,
        String budgetSummary,
        boolean fallback
) {
}