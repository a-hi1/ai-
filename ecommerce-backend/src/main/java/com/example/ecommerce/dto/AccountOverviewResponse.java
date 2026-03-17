package com.example.ecommerce.dto;

import java.util.List;

public record AccountOverviewResponse(
        AuthUserResponse profile,
        List<ProductInsightResponse> recentViews,
        List<ProductInsightResponse> recommendations
) {
}