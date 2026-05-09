package com.example.userservice.ratelimit.service;

public interface RateLimitService {

    boolean isLoginLocked(String email);

    void recordLoginFailure(String email);

    void resetLoginFailures(String email);

    boolean isResendOnCooldown(String email);

    boolean hasReachedDailyResendLimit(String email);

    void recordResend(String email);
}
