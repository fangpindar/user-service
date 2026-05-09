package com.example.userservice.ratelimit.service.impl;

import com.example.userservice.config.properties.AppProperties;
import com.example.userservice.ratelimit.service.RateLimitService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class RateLimitServiceImpl implements RateLimitService {

    private static final String KEY_LOGIN_FAIL = "login_fail:";
    private static final String KEY_RESEND_CD = "resend_cd:";
    private static final String KEY_RESEND_COUNT = "resend_count:";

    private final StringRedisTemplate redis;
    private final AppProperties props;

    public RateLimitServiceImpl(StringRedisTemplate redis, AppProperties props) {
        this.redis = redis;
        this.props = props;
    }

    @Override
    public boolean isLoginLocked(String email) {
        String value = redis.opsForValue().get(KEY_LOGIN_FAIL + email);
        if (value == null) return false;
        try {
            return Integer.parseInt(value) >= props.login().failLockThreshold();
        } catch (NumberFormatException e) {
            return false;
        }
    }

    @Override
    public void recordLoginFailure(String email) {
        String key = KEY_LOGIN_FAIL + email;
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redis.expire(key, Duration.ofMinutes(props.login().failLockWindowMinutes()));
        }
    }

    @Override
    public void resetLoginFailures(String email) {
        redis.delete(KEY_LOGIN_FAIL + email);
    }

    @Override
    public boolean isResendOnCooldown(String email) {
        return Boolean.TRUE.equals(redis.hasKey(KEY_RESEND_CD + email));
    }

    @Override
    public boolean hasReachedDailyResendLimit(String email) {
        String value = redis.opsForValue().get(KEY_RESEND_COUNT + email);
        if (value == null) return false;
        try {
            return Integer.parseInt(value) >= props.activation().resendDailyLimit();
        } catch (NumberFormatException e) {
            return false;
        }
    }

    @Override
    public void recordResend(String email) {
        String cdKey = KEY_RESEND_CD + email;
        redis.opsForValue().set(cdKey, "1", Duration.ofSeconds(props.activation().resendCooldownSeconds()));

        String countKey = KEY_RESEND_COUNT + email;
        Long count = redis.opsForValue().increment(countKey);
        if (count != null && count == 1L) {
            redis.expire(countKey, Duration.ofHours(24));
        }
    }
}
