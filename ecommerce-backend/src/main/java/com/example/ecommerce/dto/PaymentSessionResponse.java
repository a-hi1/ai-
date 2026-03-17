package com.example.ecommerce.dto;

import java.util.UUID;

public record PaymentSessionResponse(
        UUID orderId,
        String paymentUrl,
        String provider,
        String mode,
        String gatewayTradeNo
) {
}