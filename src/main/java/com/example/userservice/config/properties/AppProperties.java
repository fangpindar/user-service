package com.example.userservice.config.properties;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        @NotNull @Valid Email email,
        @NotNull @Valid Activation activation,
        @NotNull @Valid Otp otp,
        @NotNull @Valid Login login
) {
    public record Email(
            @NotBlank String from,
            String sendgridApiKey
    ) {}

    public record Activation(
            @NotBlank String baseUrl,
            @NotNull @Positive Integer tokenTtlHours,
            @NotNull @Positive Integer resendCooldownSeconds,
            @NotNull @Positive Integer resendDailyLimit
    ) {}

    public record Otp(
            @NotNull @Positive Integer ttlMinutes,
            @NotNull @Min(1) Integer maxAttempts,
            @NotNull @Min(4) Integer codeLength
    ) {}

    public record Login(
            @NotNull @Min(1) Integer failLockThreshold,
            @NotNull @Positive Integer failLockWindowMinutes
    ) {}
}
