package com.example.userservice.common.exception;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.Instant;
import java.util.List;

@JsonFormat
public record ApiError(
        Instant timestamp,
        int status,
        String error,
        String code,
        String message,
        String path,
        List<FieldError> fieldErrors
) {
    public ApiError(int status, String error, String code, String message, String path) {
        this(Instant.now(), status, error, code, message, path, null);
    }

    public ApiError(int status, String error, String code, String message, String path, List<FieldError> fieldErrors) {
        this(Instant.now(), status, error, code, message, path, fieldErrors);
    }

    public record FieldError(String field, String message) {}
}
