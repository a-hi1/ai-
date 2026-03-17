package com.example.ecommerce.dto;

public record DataAdminActionResponse(
        String message,
        DataAdminOverviewResponse overview
) {
}