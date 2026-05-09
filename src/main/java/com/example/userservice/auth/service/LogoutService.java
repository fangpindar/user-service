package com.example.userservice.auth.service;

import com.example.userservice.auth.jwt.UserPrincipal;

import java.time.Instant;

public interface LogoutService {

    /**
     * Logout the current user:
     *   1. Blacklist the access JWT (until its original expiry)
     *   2. Delete all refresh tokens for the user
     */
    void logout(UserPrincipal principal, Instant accessTokenExp);
}
