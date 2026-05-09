package com.example.userservice.auth.service;

public interface RegistrationService {

    void register(String email, String rawPassword);

    String createActivationToken(Long userId);

    String buildActivationLink(String token);
}
