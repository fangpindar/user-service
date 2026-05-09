package com.example.userservice.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Login phase 1 response. Use challengeId in /verify-otp.")
public record LoginChallengeResponse(
        @Schema(example = "550e8400-e29b-41d4-a716-446655440000")
        String challengeId,
        @Schema(example = "300", description = "OTP expires in seconds")
        long expiresInSeconds,
        String message
) {}
