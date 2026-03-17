package com.example.ecommerce.dto;

public record DataAdminStatsResponse(
        long totalProducts,
        long sampleProducts,
        long crawlerProducts,
        long manualProducts,
        long userCount,
        long orderCount,
        long cartItemCount,
        long chatMessageCount,
        long productViewCount
) {
}