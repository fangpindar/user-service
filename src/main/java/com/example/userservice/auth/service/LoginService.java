package com.example.userservice.auth.service;

public interface LoginService {

    /** Phase 1: validate credentials, issue OTP, send email. */
    OtpService.Issued startLogin(String email, String rawPassword);

    /** Phase 2: verify OTP, update last-login, issue JWT pair. */
    TokenService.TokenPair completeLogin(String challengeId,
                                         String code,
                                         String ipAddress,
                                         String userAgent);
}
