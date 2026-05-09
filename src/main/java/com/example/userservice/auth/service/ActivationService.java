package com.example.userservice.auth.service;

public interface ActivationService {

    /**
     * Read-only validation used by GET /activate to show the confirmation page
     * without consuming the token.
     */
    void validateTokenReadOnly(String token);

    /**
     * Performs actual activation. Called by POST /activate. Marks the token as used
     * and flips user status to ACTIVE.
     */
    void activate(String token);
}
