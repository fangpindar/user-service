package com.example.userservice.common.exception.domain;

import org.springframework.http.HttpStatus;

public class InvalidCredentialsException extends AppException {
    public InvalidCredentialsException() {
        super(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Invalid email or password.");
    }
}
