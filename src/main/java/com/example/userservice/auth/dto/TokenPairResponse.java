package com.example.userservice.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "JWT token pair response")
public record TokenPairResponse(
        @Schema(description = "Access token (JWT, ~15 min TTL). Use as Bearer in Authorization header.")
        String accessToken,
        @Schema(description = "Refresh token (JWT, 7 day TTL). Use in /refresh.")
        String refreshToken,
        @Schema(example = "Bearer")
        String tokenType,
        @Schema(example = "900", description = "Access token TTL in seconds")
        long expiresInSeconds
) {
    public static TokenPairResponse of(String access, String refresh, long expiresIn) {
        return new TokenPairResponse(access, refresh, "Bearer", expiresIn);
    }
}
