package com.example.userservice.email.service;

public interface EmailService {

    void sendActivationEmail(String toEmail, String activationLink);

    void sendOtpEmail(String toEmail, String code, int ttlMinutes);
}
