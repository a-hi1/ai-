package com.example.ecommerce.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record PaymentNotifyRequest(
        @NotNull UUID orderId,
        @NotBlank String status,
        String gatewayTradeNo
) {
}
