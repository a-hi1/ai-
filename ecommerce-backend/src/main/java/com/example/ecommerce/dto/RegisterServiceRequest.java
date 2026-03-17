package com.example.ecommerce.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record RegisterServiceRequest(
        @NotBlank String serviceName,
        @NotBlank String host,
        @Min(1) int port
) {
}
