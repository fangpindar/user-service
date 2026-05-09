package com.example.userservice.common.exception.domain;

import org.springframework.http.HttpStatus;

public class OtpInvalidException extends AppException {
    public OtpInvalidException(String reason) {
        super(HttpStatus.UNAUTHORIZED, "OTP_INVALID", reason);
    }

    public static OtpInvalidException expired() {
        return new OtpInvalidException("OTP challenge has expired or does not exist. Please log in again.");
    }

    public static OtpInvalidException wrongCode() {
        return new OtpInvalidException("OTP code is incorrect.");
    }

    public static OtpInvalidException tooManyAttempts() {
        return new OtpInvalidException("Too many failed attempts. Please log in again to request a new OTP.");
    }
}
