package com.example.userservice.config.properties;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(
        @NotBlank String accessSecret,
        @NotBlank String refreshSecret,
        @NotNull @Positive Integer accessTtlMinutes,
        @NotNull @Positive Integer refreshTtlDays,
        @NotBlank String issuer
) {}
