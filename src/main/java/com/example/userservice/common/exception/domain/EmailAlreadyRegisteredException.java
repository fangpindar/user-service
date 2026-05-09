package com.example.userservice.common.exception.domain;

import org.springframework.http.HttpStatus;

public class EmailAlreadyRegisteredException extends AppException {
    public EmailAlreadyRegisteredException() {
        super(HttpStatus.CONFLICT, "EMAIL_ALREADY_REGISTERED", "Email is already registered.");
    }
}
