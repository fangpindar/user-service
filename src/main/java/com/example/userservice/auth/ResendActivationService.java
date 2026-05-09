package com.example.userservice.auth;

import com.example.userservice.common.exception.domain.TooManyRequestsException;
import com.example.userservice.email.EmailService;
import com.example.userservice.ratelimit.RateLimitService;
import com.example.userservice.user.ActivationTokenRepository;
import com.example.userservice.user.User;
import com.example.userservice.user.UserRepository;
import com.example.userservice.user.UserStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

@Service
public class ResendActivationService {

    private static final Logger log = LoggerFactory.getLogger(ResendActivationService.class);

    private final UserRepository userRepository;
    private final ActivationTokenRepository tokenRepository;
    private final RegistrationService registrationService;
    private final EmailService emailService;
    private final RateLimitService rateLimitService;

    public ResendActivationService(UserRepository userRepository,
                                   ActivationTokenRepository tokenRepository,
                                   RegistrationService registrationService,
                                   EmailService emailService,
                                   RateLimitService rateLimitService) {
        this.userRepository = userRepository;
        this.tokenRepository = tokenRepository;
        this.registrationService = registrationService;
        this.emailService = emailService;
        this.rateLimitService = rateLimitService;
    }

    @Transactional
    public void resend(String email) {
        String normalized = email.trim().toLowerCase();

        // Rate limits checked first to deter abuse regardless of email validity
        if (rateLimitService.isResendOnCooldown(normalized)) {
            throw new TooManyRequestsException(
                    "Please wait before requesting another activation email.");
        }
        if (rateLimitService.hasReachedDailyResendLimit(normalized)) {
            throw new TooManyRequestsException(
                    "Daily resend limit reached. Please try again tomorrow.");
        }

        Optional<User> userOpt = userRepository.findByEmail(normalized);
        // Always record cooldown to prevent enumeration via timing
        rateLimitService.recordResend(normalized);

        if (userOpt.isEmpty()) {
            log.info("Resend requested for unknown email (silently accepted): {}", normalized);
            return;
        }
        User user = userOpt.get();
        if (user.getStatus() != UserStatus.PENDING_ACTIVATION) {
            log.info("Resend requested for non-pending user (silently accepted): id={} status={}",
                    user.getId(), user.getStatus());
            return;
        }

        tokenRepository.markAllUnusedAsUsedForUser(user.getId(), Instant.now());
        String newToken = registrationService.createActivationToken(user.getId());
        String link = registrationService.buildActivationLink(newToken);

        emailService.sendActivationEmail(normalized, link);
        log.info("Activation email resent to {}", normalized);
    }
}
