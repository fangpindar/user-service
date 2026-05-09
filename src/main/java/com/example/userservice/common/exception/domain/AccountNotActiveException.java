package com.example.userservice.common.exception.domain;

import org.springframework.http.HttpStatus;

public class AccountNotActiveException extends AppException {
    public AccountNotActiveException() {
        super(HttpStatus.FORBIDDEN, "ACCOUNT_NOT_ACTIVE",
                "Account is not active. Please activate your account via the email link first.");
    }
}
