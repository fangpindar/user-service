package com.example.userservice.common.exception.domain;

import org.springframework.http.HttpStatus;

public class ActivationTokenInvalidException extends AppException {
    public ActivationTokenInvalidException(String reason) {
        super(HttpStatus.BAD_REQUEST, "ACTIVATION_TOKEN_INVALID", reason);
    }

    public static ActivationTokenInvalidException notFound() {
        return new ActivationTokenInvalidException("Activation token is invalid or does not exist.");
    }

    public static ActivationTokenInvalidException expired() {
        return new ActivationTokenInvalidException("Activation token has expired. Please request a new one.");
    }

    public static ActivationTokenInvalidException alreadyUsed() {
        return new ActivationTokenInvalidException("Activation token has already been used.");
    }
}
