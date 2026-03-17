package com.example.ecommerce.dto;

import java.util.UUID;

public record AuthUserResponse(
        UUID id,
        String email,
        String role,
        String displayName,
        String phone,
        String city,
        String bio
) {
}