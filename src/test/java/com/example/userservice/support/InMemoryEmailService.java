package com.example.userservice.support;

import com.example.userservice.email.service.EmailService;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Test-only EmailService that records sent emails in memory.
 * Used by integration tests to assert what was "sent" without hitting SendGrid.
 */
@Service
@Primary
public class InMemoryEmailService implements EmailService {

    private final List<SentMail> sent = new CopyOnWriteArrayList<>();

    @Override
    public void sendActivationEmail(String toEmail, String activationLink) {
        sent.add(new SentMail(Kind.ACTIVATION, toEmail, activationLink, null));
    }

    @Override
    public void sendOtpEmail(String toEmail, String code, int ttlMinutes) {
        sent.add(new SentMail(Kind.OTP, toEmail, null, code));
    }

    public List<SentMail> all() {
        return new ArrayList<>(sent);
    }

    public Optional<SentMail> lastTo(String email) {
        return sent.stream()
                .filter(m -> m.toEmail().equalsIgnoreCase(email))
                .reduce((a, b) -> b);
    }

    public Optional<SentMail> lastActivationTo(String email) {
        return sent.stream()
                .filter(m -> m.kind() == Kind.ACTIVATION && m.toEmail().equalsIgnoreCase(email))
                .reduce((a, b) -> b);
    }

    public Optional<SentMail> lastOtpTo(String email) {
        return sent.stream()
                .filter(m -> m.kind() == Kind.OTP && m.toEmail().equalsIgnoreCase(email))
                .reduce((a, b) -> b);
    }

    public void clear() {
        sent.clear();
    }

    public enum Kind { ACTIVATION, OTP }

    public record SentMail(Kind kind, String toEmail, String activationLink, String otpCode) {}
}
