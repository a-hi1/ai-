package com.example.ecommerce.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

public record HeartbeatRequest(
        @NotNull UUID serviceId
) {
}
