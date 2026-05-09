package com.example.userservice.auth.service;

import java.time.Instant;

public interface TokenService {

    TokenPair issueTokenPair(Long userId, String email);

    /**
     * Validate refresh token, delete old key (rotation), then issue new pair.
     * Returns null if the refresh token is invalid or already used (replayed).
     */
    TokenPair rotateRefresh(String refreshToken, String email);

    void revokeAccess(String accessJti, Instant accessExp);

    void revokeAllRefreshForUser(Long userId);

    boolean isAccessBlacklisted(String jti);

    record TokenPair(String accessToken, String refreshToken, long expiresInSeconds) {}
}
