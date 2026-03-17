package com.example.ecommerce.dto;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;

public record ChatRequest(
                @NotBlank String userId,
        @NotBlank String message
) {
        public UUID resolvedUserId() {
                String candidate = userId == null ? "" : userId.trim();
                if (candidate.isEmpty()) {
                        return UUID.nameUUIDFromBytes("guest".getBytes(StandardCharsets.UTF_8));
                }
                try {
                        return UUID.fromString(candidate);
                } catch (IllegalArgumentException ignored) {
                        return UUID.nameUUIDFromBytes(candidate.getBytes(StandardCharsets.UTF_8));
                }
        }
}
