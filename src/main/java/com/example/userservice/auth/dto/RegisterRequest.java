package com.example.userservice.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Schema(description = "Registration request")
public record RegisterRequest(
        @Schema(example = "user@example.com")
        @NotBlank @Email @Size(max = 255)
        String email,

        @Schema(example = "Test1234", description = "Min 8 chars, must contain letter and digit")
        @NotBlank
        @Size(min = 8, max = 100)
        @Pattern(
                regexp = "^(?=.*[A-Za-z])(?=.*\\d).+$",
                message = "Password must contain at least one letter and one digit"
        )
        String password
) {}
