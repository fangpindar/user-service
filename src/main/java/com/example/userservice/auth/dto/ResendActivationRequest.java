package com.example.userservice.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "Resend activation email request")
public record ResendActivationRequest(
        @Schema(example = "user@example.com")
        @NotBlank @Email
        String email
) {}
