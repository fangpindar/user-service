package com.example.userservice.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "Refresh token request")
public record RefreshRequest(
        @Schema(description = "Refresh token from previous login or refresh")
        @NotBlank
        String refreshToken
) {}
