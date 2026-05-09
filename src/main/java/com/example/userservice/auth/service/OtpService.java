package com.example.userservice.auth.service;

public interface OtpService {

    Issued issue(Long userId);

    Long verify(String challengeId, String code);

    record Issued(String challengeId, String code, long expiresInSeconds) {}
}
