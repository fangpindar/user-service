package com.example.userservice.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

@Schema(description = "OTP verification request (Phase 2)")
public record VerifyOtpRequest(
        @Schema(example = "550e8400-e29b-41d4-a716-446655440000")
        @NotBlank
        String challengeId,

        @Schema(example = "123456")
        @NotBlank
        @Pattern(regexp = "^[0-9]{6}$", message = "OTP code must be 6 digits")
        String code
) {}
