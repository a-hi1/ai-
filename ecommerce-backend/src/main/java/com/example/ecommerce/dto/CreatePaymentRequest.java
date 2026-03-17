package com.example.ecommerce.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

public record CreatePaymentRequest(
        @NotNull UUID orderId,
        String preferredMode
) {
}