package com.example.userservice.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "Login request (Phase 1: credentials)")
public record LoginRequest(
        @Schema(example = "user@example.com")
        @NotBlank @Email
        String email,

        @Schema(example = "Test1234")
        @NotBlank
        String password
) {}
