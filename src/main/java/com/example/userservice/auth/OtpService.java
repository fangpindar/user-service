package com.example.userservice.auth;

import com.example.userservice.common.exception.domain.OtpInvalidException;
import com.example.userservice.common.util.SecureTokenGenerator;
import com.example.userservice.config.properties.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

@Service
public class OtpService {

    private static final Logger log = LoggerFactory.getLogger(OtpService.class);
    private static final String KEY_PREFIX = "otp:";
    private static final String FIELD_USER_ID = "userId";
    private static final String FIELD_CODE_HASH = "codeHash";
    private static final String FIELD_ATTEMPTS = "attempts";

    private final StringRedisTemplate redis;
    private final SecureTokenGenerator tokenGenerator;
    private final PasswordEncoder passwordEncoder;
    private final AppProperties appProperties;

    public OtpService(StringRedisTemplate redis,
                      SecureTokenGenerator tokenGenerator,
                      PasswordEncoder passwordEncoder,
                      AppProperties appProperties) {
        this.redis = redis;
        this.tokenGenerator = tokenGenerator;
        this.passwordEncoder = passwordEncoder;
        this.appProperties = appProperties;
    }

    public Issued issue(Long userId) {
        String code = tokenGenerator.generateNumericCode(appProperties.otp().codeLength());
        String challengeId = UUID.randomUUID().toString();
        String key = KEY_PREFIX + challengeId;

        HashOperations<String, Object, Object> hash = redis.opsForHash();
        hash.put(key, FIELD_USER_ID, String.valueOf(userId));
        hash.put(key, FIELD_CODE_HASH, passwordEncoder.encode(code));
        hash.put(key, FIELD_ATTEMPTS, "0");
        redis.expire(key, Duration.ofMinutes(appProperties.otp().ttlMinutes()));

        log.debug("Issued OTP challenge {} for user {}", challengeId, userId);
        return new Issued(challengeId, code, appProperties.otp().ttlMinutes() * 60L);
    }

    public Long verify(String challengeId, String code) {
        String key = KEY_PREFIX + challengeId;
        HashOperations<String, Object, Object> hash = redis.opsForHash();
        Map<Object, Object> entries = hash.entries(key);

        if (entries.isEmpty()) {
            throw OtpInvalidException.expired();
        }

        int attempts = parseInt(entries.get(FIELD_ATTEMPTS), 0);
        int max = appProperties.otp().maxAttempts();
        if (attempts >= max) {
            redis.delete(key);
            throw OtpInvalidException.tooManyAttempts();
        }

        String storedHash = String.valueOf(entries.get(FIELD_CODE_HASH));
        if (!passwordEncoder.matches(code, storedHash)) {
            Long newAttempts = hash.increment(key, FIELD_ATTEMPTS, 1);
            if (newAttempts != null && newAttempts >= max) {
                redis.delete(key);
                throw OtpInvalidException.tooManyAttempts();
            }
            throw OtpInvalidException.wrongCode();
        }

        Long userId = Long.parseLong(String.valueOf(entries.get(FIELD_USER_ID)));
        redis.delete(key);
        return userId;
    }

    private int parseInt(Object value, int defaultValue) {
        if (value == null) return defaultValue;
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public record Issued(String challengeId, String code, long expiresInSeconds) {}
}
