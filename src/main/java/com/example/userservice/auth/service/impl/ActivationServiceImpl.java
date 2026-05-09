package com.example.userservice.auth.service.impl;

import com.example.userservice.auth.service.ActivationService;
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
public class ActivationServiceImpl implements ActivationService {

    private static final Logger log = LoggerFactory.getLogger(ActivationServiceImpl.class);

    private final ActivationTokenRepository tokenRepository;
    private final UserRepository userRepository;

    public ActivationServiceImpl(ActivationTokenRepository tokenRepository,
                                 UserRepository userRepository) {
        this.tokenRepository = tokenRepository;
        this.userRepository = userRepository;
    }

    @Override
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

    @Override
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
