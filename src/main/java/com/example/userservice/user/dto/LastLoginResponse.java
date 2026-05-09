package com.example.userservice.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Schema(description = "Last login response")
public record LastLoginResponse(
        @Schema(example = "user@example.com")
        String email,
        @Schema(description = "Most recent successful login timestamp (UTC). " +
                "May represent the current session if you just logged in.",
                example = "2026-05-09T07:30:00Z")
        Instant lastLoginAt
) {}
