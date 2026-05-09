package com.example.userservice.auth;

import com.example.userservice.common.exception.domain.EmailAlreadyRegisteredException;
import com.example.userservice.common.util.SecureTokenGenerator;
import com.example.userservice.config.properties.AppProperties;
import com.example.userservice.email.EmailService;
import com.example.userservice.user.ActivationToken;
import com.example.userservice.user.ActivationTokenRepository;
import com.example.userservice.user.User;
import com.example.userservice.user.UserRepository;
import com.example.userservice.user.UserStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

@Service
public class RegistrationService {

    private static final Logger log = LoggerFactory.getLogger(RegistrationService.class);

    private final UserRepository userRepository;
    private final ActivationTokenRepository tokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final SecureTokenGenerator tokenGenerator;
    private final EmailService emailService;
    private final AppProperties appProperties;

    public RegistrationService(UserRepository userRepository,
                               ActivationTokenRepository tokenRepository,
                               PasswordEncoder passwordEncoder,
                               SecureTokenGenerator tokenGenerator,
                               EmailService emailService,
                               AppProperties appProperties) {
        this.userRepository = userRepository;
        this.tokenRepository = tokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenGenerator = tokenGenerator;
        this.emailService = emailService;
        this.appProperties = appProperties;
    }

    @Transactional
    public void register(String email, String rawPassword) {
        String normalizedEmail = email.trim().toLowerCase();

        if (userRepository.existsByEmail(normalizedEmail)) {
            throw new EmailAlreadyRegisteredException();
        }

        User user = new User();
        user.setEmail(normalizedEmail);
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        user.setStatus(UserStatus.PENDING_ACTIVATION);
        userRepository.save(user);

        String activationToken = createActivationToken(user.getId());
        String link = buildActivationLink(activationToken);

        emailService.sendActivationEmail(normalizedEmail, link);
        log.info("User registered: id={}, email={}", user.getId(), normalizedEmail);
    }

    @Transactional
    public String createActivationToken(Long userId) {
        String tokenValue = tokenGenerator.generateHexToken();
        ActivationToken token = new ActivationToken();
        token.setUserId(userId);
        token.setToken(tokenValue);
        token.setExpiresAt(Instant.now().plus(Duration.ofHours(appProperties.activation().tokenTtlHours())));
        token.setCreatedAt(Instant.now());
        tokenRepository.save(token);
        return tokenValue;
    }

    public String buildActivationLink(String token) {
        String base = appProperties.activation().baseUrl();
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base + "/api/v1/auth/activate?token=" + token;
    }
}
