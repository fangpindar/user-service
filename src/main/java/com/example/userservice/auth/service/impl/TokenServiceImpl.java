package com.example.userservice.auth.service.impl;

import com.example.userservice.auth.jwt.JwtTokenProvider;
import com.example.userservice.auth.jwt.JwtTokenProvider.IssuedToken;
import com.example.userservice.auth.jwt.JwtTokenProvider.ParsedToken;
import com.example.userservice.auth.service.TokenService;
import com.example.userservice.config.properties.JwtProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

@Service
public class TokenServiceImpl implements TokenService {

    private static final Logger log = LoggerFactory.getLogger(TokenServiceImpl.class);
    private static final String KEY_REFRESH_PREFIX = "refresh:";
    private static final String KEY_BLACKLIST_PREFIX = "blacklist:";

    private final JwtTokenProvider jwtProvider;
    private final StringRedisTemplate redis;
    private final JwtProperties jwtProps;

    public TokenServiceImpl(JwtTokenProvider jwtProvider,
                            StringRedisTemplate redis,
                            JwtProperties jwtProps) {
        this.jwtProvider = jwtProvider;
        this.redis = redis;
        this.jwtProps = jwtProps;
    }

    @Override
    public TokenPair issueTokenPair(Long userId, String email) {
        IssuedToken access = jwtProvider.issueAccess(userId, email);
        IssuedToken refresh = jwtProvider.issueRefresh(userId);

        redis.opsForValue().set(
                refreshKey(userId, refresh.jti()),
                "1",
                Duration.ofDays(jwtProps.refreshTtlDays())
        );

        long accessTtlSeconds = Duration.ofMinutes(jwtProps.accessTtlMinutes()).toSeconds();
        return new TokenPair(access.token(), refresh.token(), accessTtlSeconds);
    }

    @Override
    public TokenPair rotateRefresh(String refreshToken, String email) {
        ParsedToken parsed;
        try {
            parsed = jwtProvider.parseRefresh(refreshToken);
        } catch (Exception ex) {
            log.warn("Invalid refresh token: {}", ex.getMessage());
            return null;
        }

        String oldKey = refreshKey(parsed.userId(), parsed.jti());
        Boolean deleted = redis.delete(oldKey);
        if (!Boolean.TRUE.equals(deleted)) {
            log.warn("Refresh token replay detected for user {} jti {}", parsed.userId(), parsed.jti());
            return null;
        }

        return issueTokenPair(parsed.userId(), email);
    }

    @Override
    public void revokeAccess(String accessJti, Instant accessExp) {
        long ttlSeconds = Duration.between(Instant.now(), accessExp).getSeconds();
        if (ttlSeconds > 0) {
            redis.opsForValue().set(blacklistKey(accessJti), "1", Duration.ofSeconds(ttlSeconds));
        }
    }

    @Override
    public void revokeAllRefreshForUser(Long userId) {
        Set<String> keys = redis.keys(KEY_REFRESH_PREFIX + userId + ":*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    @Override
    public boolean isAccessBlacklisted(String jti) {
        return Boolean.TRUE.equals(redis.hasKey(blacklistKey(jti)));
    }

    private String refreshKey(Long userId, String jti) {
        return KEY_REFRESH_PREFIX + userId + ":" + jti;
    }

    private String blacklistKey(String jti) {
        return KEY_BLACKLIST_PREFIX + jti;
    }
}
