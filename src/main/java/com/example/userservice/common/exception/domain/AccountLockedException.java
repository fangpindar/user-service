package com.example.userservice.common.exception.domain;

import org.springframework.http.HttpStatus;

public class AccountLockedException extends AppException {
    public AccountLockedException() {
        super(HttpStatus.LOCKED, "ACCOUNT_LOCKED",
                "Account temporarily locked due to too many failed login attempts. Please try again later.");
    }
}
