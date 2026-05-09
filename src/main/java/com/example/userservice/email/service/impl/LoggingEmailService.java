package com.example.userservice.email.service.impl;

import com.example.userservice.email.service.EmailService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fallback email service used when SENDGRID_API_KEY is not configured.
 * Logs the email content to stdout instead of sending. Useful for local development
 * without a SendGrid account.
 */
public class LoggingEmailService implements EmailService {

    private static final Logger log = LoggerFactory.getLogger(LoggingEmailService.class);

    @Override
    public void sendActivationEmail(String toEmail, String activationLink) {
        log.warn("[LOGGING-ONLY] Activation email to {} | link: {}", toEmail, activationLink);
        System.out.println("=========================================================");
        System.out.println("[ACTIVATION EMAIL] To: " + toEmail);
        System.out.println("[ACTIVATION LINK ] " + activationLink);
        System.out.println("=========================================================");
    }

    @Override
    public void sendOtpEmail(String toEmail, String code, int ttlMinutes) {
        log.warn("[LOGGING-ONLY] OTP email to {} | code: {}", toEmail, code);
        System.out.println("=========================================================");
        System.out.println("[OTP EMAIL] To: " + toEmail);
        System.out.println("[OTP CODE ] " + code + " (expires in " + ttlMinutes + " min)");
        System.out.println("=========================================================");
    }
}
