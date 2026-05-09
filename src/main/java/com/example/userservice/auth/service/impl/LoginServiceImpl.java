package com.example.userservice.auth.service.impl;

import com.example.userservice.auth.service.LoginService;
import com.example.userservice.auth.service.OtpService;
import com.example.userservice.auth.service.TokenService;
import com.example.userservice.common.exception.domain.AccountLockedException;
import com.example.userservice.common.exception.domain.AccountNotActiveException;
import com.example.userservice.common.exception.domain.InvalidCredentialsException;
import com.example.userservice.config.properties.AppProperties;
import com.example.userservice.email.service.EmailService;
import com.example.userservice.ratelimit.service.RateLimitService;
import com.example.userservice.user.entity.LoginAudit;
import com.example.userservice.user.entity.User;
import com.example.userservice.user.entity.UserStatus;
import com.example.userservice.user.repository.LoginAuditRepository;
import com.example.userservice.user.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

@Service
public class LoginServiceImpl implements LoginService {

    private static final Logger log = LoggerFactory.getLogger(LoginServiceImpl.class);

    private final UserRepository userRepository;
    private final LoginAuditRepository loginAuditRepository;
    private final PasswordEncoder passwordEncoder;
    private final RateLimitService rateLimitService;
    private final OtpService otpService;
    private final EmailService emailService;
    private final TokenService tokenService;
    private final AppProperties appProperties;

    public LoginServiceImpl(UserRepository userRepository,
                            LoginAuditRepository loginAuditRepository,
                            PasswordEncoder passwordEncoder,
                            RateLimitService rateLimitService,
                            OtpService otpService,
                            EmailService emailService,
                            TokenService tokenService,
                            AppProperties appProperties) {
        this.userRepository = userRepository;
        this.loginAuditRepository = loginAuditRepository;
        this.passwordEncoder = passwordEncoder;
        this.rateLimitService = rateLimitService;
        this.otpService = otpService;
        this.emailService = emailService;
        this.tokenService = tokenService;
        this.appProperties = appProperties;
    }

    @Override
    @Transactional(readOnly = true)
    public OtpService.Issued startLogin(String email, String rawPassword) {
        String normalized = email.trim().toLowerCase();

        if (rateLimitService.isLoginLocked(normalized)) {
            throw new AccountLockedException();
        }

        Optional<User> userOpt = userRepository.findByEmail(normalized);
        if (userOpt.isEmpty() || !passwordEncoder.matches(rawPassword, userOpt.get().getPasswordHash())) {
            rateLimitService.recordLoginFailure(normalized);
            throw new InvalidCredentialsException();
        }

        User user = userOpt.get();
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new AccountNotActiveException();
        }

        OtpService.Issued issued = otpService.issue(user.getId());
        emailService.sendOtpEmail(normalized, issued.code(), appProperties.otp().ttlMinutes());
        log.info("Login phase 1 OK for user id={}, OTP issued", user.getId());
        return issued;
    }

    @Override
    @Transactional
    public TokenService.TokenPair completeLogin(String challengeId,
                                                String code,
                                                String ipAddress,
                                                String userAgent) {
        Long userId = otpService.verify(challengeId, code);

        User user = userRepository.findById(userId)
                .orElseThrow(InvalidCredentialsException::new);

        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new AccountNotActiveException();
        }

        rateLimitService.resetLoginFailures(user.getEmail());

        Instant now = Instant.now();
        userRepository.updateLastLoginAt(user.getId(), now);

        LoginAudit audit = new LoginAudit();
        audit.setUserId(user.getId());
        audit.setLoginAt(now);
        audit.setIpAddress(truncate(ipAddress, 45));
        audit.setUserAgent(truncate(userAgent, 500));
        loginAuditRepository.save(audit);

        TokenService.TokenPair pair = tokenService.issueTokenPair(user.getId(), user.getEmail());
        log.info("Login completed for user id={}", user.getId());
        return pair;
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
