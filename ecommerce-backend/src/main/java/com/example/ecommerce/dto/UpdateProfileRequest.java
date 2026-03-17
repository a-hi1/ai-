package com.example.ecommerce.dto;

import jakarta.validation.constraints.Size;

public record UpdateProfileRequest(
        @Size(max = 128) String displayName,
        @Size(max = 32) String phone,
        @Size(max = 128) String city,
        @Size(max = 512) String bio
) {
}