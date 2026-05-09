package com.example.userservice.auth;

import com.example.userservice.common.exception.domain.ActivationTokenInvalidException;
import com.example.userservice.user.ActivationToken;
import com.example.userservice.user.ActivationTokenRepository;
import com.example.userservice.user.User;
import com.example.userservice.user.UserRepository;
import com.example.userservice.user.UserStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class ActivationService {

    private static final Logger log = LoggerFactory.getLogger(ActivationService.class);

    private final ActivationTokenRepository tokenRepository;
    private final UserRepository userRepository;

    public ActivationService(ActivationTokenRepository tokenRepository,
                             UserRepository userRepository) {
        this.tokenRepository = tokenRepository;
        this.userRepository = userRepository;
    }

    /**
     * Read-only validation used by GET /activate to show the confirmation page
     * without consuming the token.
     */
    @Transactional(readOnly = true)
    public void validateTokenReadOnly(String token) {
        ActivationToken record = tokenRepository.findByToken(token)
                .orElseThrow(ActivationTokenInvalidException::notFound);
        if (record.isUsed()) {
            throw ActivationTokenInvalidException.alreadyUsed();
        }
        if (record.isExpired()) {
            throw ActivationTokenInvalidException.expired();
        }
    }

    /**
     * Performs actual activation. Called by POST /activate. Marks the token as used
     * and flips user status to ACTIVE.
     */
    @Transactional
    public void activate(String token) {
        ActivationToken record = tokenRepository.findByToken(token)
                .orElseThrow(ActivationTokenInvalidException::notFound);
        if (record.isUsed()) {
            throw ActivationTokenInvalidException.alreadyUsed();
        }
        if (record.isExpired()) {
            throw ActivationTokenInvalidException.expired();
        }

        User user = userRepository.findById(record.getUserId())
                .orElseThrow(ActivationTokenInvalidException::notFound);

        if (user.getStatus() != UserStatus.PENDING_ACTIVATION) {
            // Already activated (or locked/deactivated). Treat as success for idempotency
            // when status is ACTIVE; otherwise treat as invalid.
            if (user.getStatus() == UserStatus.ACTIVE) {
                record.setUsedAt(Instant.now());
                tokenRepository.save(record);
                return;
            }
            throw ActivationTokenInvalidException.notFound();
        }

        user.setStatus(UserStatus.ACTIVE);
        userRepository.save(user);

        record.setUsedAt(Instant.now());
        tokenRepository.save(record);

        log.info("User activated: id={}, email={}", user.getId(), user.getEmail());
    }
}
