package com.example.ecommerce.dto;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

public record CreateOrderRequest(
        @NotNull UUID userId,
        @NotNull @DecimalMin("0.0") BigDecimal totalAmount
) {
}
