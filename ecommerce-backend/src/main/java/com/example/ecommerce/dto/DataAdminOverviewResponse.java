package com.example.ecommerce.dto;

import java.util.List;

public record DataAdminOverviewResponse(
        DataAdminStatsResponse stats,
        List<ManagedProductResponse> recentProducts
) {
}