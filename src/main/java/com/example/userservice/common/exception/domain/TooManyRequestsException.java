package com.example.userservice.common.exception.domain;

import org.springframework.http.HttpStatus;

public class TooManyRequestsException extends AppException {
    public TooManyRequestsException(String reason) {
        super(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_REQUESTS", reason);
    }
}
