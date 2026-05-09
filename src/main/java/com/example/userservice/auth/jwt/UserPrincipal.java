package com.example.userservice.auth.jwt;

public record UserPrincipal(Long userId, String email, String accessJti) {}
